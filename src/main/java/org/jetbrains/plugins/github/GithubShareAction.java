/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.github;

import consulo.application.Application;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.dataContext.DataSink;
import consulo.dataContext.TypeSafeDataProvider;
import consulo.git.localize.GitLocalize;
import consulo.github.icon.GitHubIconGroup;
import consulo.language.editor.PlatformDataKeys;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.*;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.Splitter;
import consulo.util.collection.ContainerUtil;
import consulo.util.dataholder.Key;
import consulo.util.lang.ref.Ref;
import consulo.versionControlSystem.CommitMessage;
import consulo.versionControlSystem.CommitMessageFactory;
import consulo.versionControlSystem.VcsDataKeys;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.versionControlSystem.ui.awt.ChangesBrowserTree;
import consulo.versionControlSystem.ui.awt.LegacyComponentFactory;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.DialogManager;
import git4idea.GitLocalBranch;
import git4idea.GitUtil;
import git4idea.actions.BasicAction;
import git4idea.actions.GitInit;
import git4idea.commands.*;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitFileUtils;
import git4idea.util.GitUIUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.plugins.github.api.GithubApiUtil;
import org.jetbrains.plugins.github.api.GithubRepo;
import org.jetbrains.plugins.github.api.GithubUserDetailed;
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationCanceledException;
import org.jetbrains.plugins.github.ui.GithubShareDialog;
import org.jetbrains.plugins.github.util.GithubAuthData;
import org.jetbrains.plugins.github.util.GithubNotifications;
import org.jetbrains.plugins.github.util.GithubUrlUtil;
import org.jetbrains.plugins.github.util.GithubUtil;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import static org.jetbrains.plugins.github.util.GithubUtil.setVisibleEnabled;

/**
 * @author oleg
 */
public class GithubShareAction extends DumbAwareAction {
    private static final Logger LOG = GithubUtil.LOG;

    public GithubShareAction() {
        super("Share project on GitHub", "Easily share project on GitHub", GitHubIconGroup.github_icon());
    }

    @Override
    @RequiredUIAccess
    public void update(AnActionEvent e) {
        final Project project = e.getData(PlatformDataKeys.PROJECT);
        if (project == null || project.isDefault()) {
            setVisibleEnabled(e, false, false);
            return;
        }
        setVisibleEnabled(e, true, true);
    }

    // get gitRepository
    // check for existing git repo
    // check available repos and privateRepo access (net)
    // Show dialog (window)
    // create GitHub repo (net)
    // create local git repo (if not exist)
    // add GitHub as a remote host
    // make first commit
    // push everything (net)
    @Override
    @RequiredUIAccess
    public void actionPerformed(final AnActionEvent e) {
        final Project project = e.getData(PlatformDataKeys.PROJECT);
        final VirtualFile file = e.getData(PlatformDataKeys.VIRTUAL_FILE);

        if (project == null || project.isDisposed()) {
            return;
        }

        shareProjectOnGithub(project, file);
    }

    @RequiredUIAccess
    public static void shareProjectOnGithub(@Nonnull final Project project, @Nullable final VirtualFile file) {
        BasicAction.saveAll();

        // get gitRepository
        final GitRepository gitRepository = GithubUtil.getGitRepository(project, file);
        final boolean gitDetected = gitRepository != null;
        final VirtualFile root = gitDetected ? gitRepository.getRoot() : project.getBaseDir();

        // check for existing git repo
        boolean externalRemoteDetected = false;
        if (gitDetected) {
            final String githubRemote = GithubUtil.findGithubRemoteUrl(gitRepository);
            if (githubRemote != null) {
                GithubNotifications.showInfoURL(project, "Project is already on GitHub", "GitHub", githubRemote);
                return;
            }
            externalRemoteDetected = !gitRepository.getRemotes().isEmpty();
        }

        // get available GitHub repos with modal progress
        final GithubInfo githubInfo = loadGithubInfoWithModal(project);
        if (githubInfo == null) {
            return;
        }

        // Show dialog (window)
        final GithubShareDialog shareDialog = new GithubShareDialog(project, githubInfo.getRepositoryNames(),
            githubInfo.getUser().canCreatePrivateRepo()
        );
        DialogManager.show(shareDialog);
        if (!shareDialog.isOK()) {
            return;
        }
        final boolean isPrivate = shareDialog.isPrivate();
        final String name = shareDialog.getRepositoryName();
        final String description = shareDialog.getDescription();

        // finish the job in background
        final boolean finalExternalRemoteDetected = externalRemoteDetected;
        new Task.Backgroundable(project, "Sharing project on GitHub...") {
            @Override
            @RequiredUIAccess
            public void run(@Nonnull ProgressIndicator indicator) {
                // create GitHub repo (network)
                LOG.info("Creating GitHub repository");
                indicator.setText("Creating GitHub repository...");
                final String url = createGithubRepository(project, githubInfo.getAuthData(), name, description,
                    isPrivate
                );
                if (url == null) {
                    return;
                }
                LOG.info("Successfully created GitHub repository");

                // creating empty git repo if git is not initialized
                LOG.info("Binding local project with GitHub");
                if (!gitDetected) {
                    LOG.info("No git detected, creating empty git repo");
                    indicator.setText("Creating empty git repo...");
                    if (!createEmptyGitRepository(project, root, indicator)) {
                        return;
                    }
                }

                GitRepositoryManager repositoryManager = GitUtil.getRepositoryManager(project);
                final GitRepository repository = repositoryManager.getRepositoryForRoot(root);
                LOG.assertTrue(repository != null, "GitRepository is null for root " + root);

                final String remoteUrl = GithubUrlUtil.getGitHost() + "/" + githubInfo.getUser().getLogin() + "/" +
                    name + ".git";
                final String remoteName = finalExternalRemoteDetected ? "github" : "origin";

                //git remote add origin git@github.com:login/name.git
                LOG.info("Adding GitHub as a remote host");
                indicator.setText("Adding GitHub as a remote host...");
                if (!addGithubRemote(project, root, remoteName, remoteUrl, repository)) {
                    return;
                }

                // create sample commit for binding project
                if (!performFirstCommitIfRequired(project, root, repository, indicator, name, url)) {
                    return;
                }

                //git push origin master
                LOG.info("Pushing to github master");
                indicator.setText("Pushing to github master...");
                if (!pushCurrentBranch(project, repository, remoteName, remoteUrl, name, url)) {
                    return;
                }

                GithubNotifications.showInfoURL(project, "Successfully shared project on GitHub", name, url);
            }
        }.queue();
    }

    @Nullable
    @RequiredUIAccess
    private static GithubInfo loadGithubInfoWithModal(@Nonnull final Project project) {
        try {
            return GithubUtil.computeValueInModal(project, "Access to GitHub", indicator -> {
                // get existing github repos (network) and validate auth data
                final Ref<List<GithubRepo>> availableReposRef = new Ref<>();
                final GithubAuthData auth = GithubUtil.runAndGetValidAuth(
                    project,
                    indicator,
                    authData -> availableReposRef.set(GithubApiUtil.getUserRepos(authData))
                );
                final HashSet<String> names = new HashSet<>();
                for (GithubRepo info : availableReposRef.get()) {
                    names.add(info.getName());
                }

                // check access to private repos (network)
                final GithubUserDetailed userInfo = GithubApiUtil.getCurrentUserDetailed(auth);
                return new GithubInfo(auth, userInfo, names);
            });
        }
        catch (GithubAuthenticationCanceledException e) {
            return null;
        }
        catch (IOException e) {
            GithubNotifications.showErrorDialog(project, "Failed to connect to GitHub", e);
            return null;
        }
    }

    @Nullable
    private static String createGithubRepository(
        @Nonnull Project project,
        @Nonnull GithubAuthData auth,
        @Nonnull String name,
        @Nonnull String description,
        boolean isPrivate
    ) {

        try {
            GithubRepo response = GithubApiUtil.createRepo(auth, name, description, !isPrivate);
            return response.getHtmlUrl();
        }
        catch (IOException e) {
            GithubNotifications.showError(project, "Failed to create GitHub Repository", e);
            return null;
        }
    }

    @RequiredUIAccess
    private static boolean createEmptyGitRepository(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull ProgressIndicator indicator
    ) {
        final GitLineHandler h = new GitLineHandler(project, root, GitCommand.INIT);
        GitHandlerUtil.runInCurrentThread(h, indicator, true, GitLocalize.initializingTitle());
        if (!h.errors().isEmpty()) {
            GitUIUtil.showOperationErrors(project, h.errors(), LocalizeValue.localizeTODO("git init"));
            LOG.info("Failed to create empty git repo: " + h.errors());
            return false;
        }
        GitInit.refreshAndConfigureVcsMappings(project, root, root.getPath());
        return true;
    }

    private static boolean addGithubRemote(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull String remoteName,
        @Nonnull String remoteUrl,
        @Nonnull GitRepository repository
    ) {
        final GitSimpleHandler addRemoteHandler = new GitSimpleHandler(project, root, GitCommand.REMOTE);
        addRemoteHandler.setSilent(true);
        addRemoteHandler.addParameters("add", remoteName, remoteUrl);
        try {
            addRemoteHandler.run();
            repository.update();
            if (addRemoteHandler.getExitCode() != 0) {
                GithubNotifications.showError(project, "Failed to add GitHub repository as remote",
                    "Failed to add GitHub repository as remote"
                );
                return false;
            }
        }
        catch (VcsException e) {
            GithubNotifications.showError(project, "Failed to add GitHub repository as remote", e);
            return false;
        }
        return true;
    }

    private static boolean performFirstCommitIfRequired(
        @Nonnull final Project project,
        @Nonnull VirtualFile root,
        @Nonnull GitRepository repository,
        @Nonnull ProgressIndicator indicator,
        @Nonnull String name,
        @Nonnull String url
    ) {
        // check if there is no commits
        if (!repository.isFresh()) {
            return true;
        }

        LOG.info("Trying to commit");
        try {
            LOG.info("Adding files for commit");
            indicator.setText("Adding files to git...");

            // ask for files to add
            final List<VirtualFile> trackedFiles = ChangeListManager.getInstance(project).getAffectedFiles();
            final Collection<VirtualFile> untrackedFiles = repository.getUntrackedFilesHolder()
                .retrieveUntrackedFiles();
            final List<VirtualFile> allFiles = new ArrayList<>();
            allFiles.addAll(trackedFiles);
            allFiles.addAll(untrackedFiles);

            final Ref<GithubUntrackedFilesDialog> dialogRef = new Ref<>();
            Application.get().invokeAndWait(
                () -> {
                    GithubUntrackedFilesDialog dialog = new GithubUntrackedFilesDialog(project, allFiles);
                    if (!trackedFiles.isEmpty()) {
                        dialog.myFileList.setIncludedChanges(trackedFiles);
                    }
                    DialogManager.show(dialog);
                    dialogRef.set(dialog);
                },
                indicator.getModalityState()
            );
            final GithubUntrackedFilesDialog dialog = dialogRef.get();

            final Collection<VirtualFile> files2commit = dialog.getSelectedFiles();
            if (!dialog.isOK() || files2commit.isEmpty()) {
                GithubNotifications.showInfoURL(project, "Successfully created empty repository on GitHub", name, url);
                return false;
            }

            Collection<VirtualFile> files2add = ContainerUtil.intersection(untrackedFiles, files2commit);
            Collection<VirtualFile> files2rm = ContainerUtil.subtract(trackedFiles, files2commit);
            Collection<VirtualFile> modified = new HashSet<>(trackedFiles);
            modified.addAll(files2commit);

            GitFileUtils.addFiles(project, root, files2add);
            GitFileUtils.deleteFilesFromCache(project, root, files2rm);

            // commit
            LOG.info("Performing commit");
            indicator.setText("Performing commit...");
            GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.COMMIT);
            handler.addParameters("-m", dialog.getCommitMessage());
            handler.endOptions();
            handler.run();

            VcsFileUtil.markFilesDirty(project, modified);
        }
        catch (VcsException e) {
            LOG.warn(e);
            GithubNotifications.showErrorURL(
                project,
                "Can't finish GitHub sharing process",
                "Successfully created project ",
                "'" + name + "'",
                " on GitHub, but initial commit failed:<br/>" + e.getMessage(),
                url
            );
            return false;
        }
        LOG.info("Successfully created initial commit");
        return true;
    }

    private static boolean pushCurrentBranch(
        @Nonnull Project project,
        @Nonnull GitRepository repository,
        @Nonnull String remoteName,
        @Nonnull String remoteUrl,
        @Nonnull String name,
        @Nonnull String url
    ) {
        Git git = Git.getInstance();

        GitLocalBranch currentBranch = repository.getCurrentBranch();
        if (currentBranch == null) {
            GithubNotifications.showErrorURL(
                project,
                "Can't finish GitHub sharing process",
                "Successfully created project ",
                "'" + name + "'",
                " on GitHub, but initial push failed: no current branch",
                url
            );
            return false;
        }
        GitCommandResult result = git.push(repository, remoteName, remoteUrl, null, currentBranch.getName(), true);
        if (!result.success()) {
            GithubNotifications.showErrorURL(project,
                "Can't finish GitHub sharing process",
                "Successfully created project ",
                "'" + name + "'",
                " on GitHub, but initial push failed:<br/>" + result.getErrorOutputAsHtmlString(), url
            );
            return false;
        }
        return true;
    }

    public static class GithubUntrackedFilesDialog extends DialogWrapper implements TypeSafeDataProvider {
        @Nonnull
        private final Project myProject;
        private CommitMessage myCommitMessagePanel;

        @Nonnull
        private final ChangesBrowserTree<VirtualFile> myFileList;

        public GithubUntrackedFilesDialog(@Nonnull Project project, @Nonnull List<VirtualFile> untrackedFiles) {
            super(project);
            myProject = project;
            myFileList = Application.get().getInstance(LegacyComponentFactory.class).createVirtualFileList(project,
                untrackedFiles,
                true,
                false);
            myFileList.setChangesToDisplay(untrackedFiles);

            setTitle("Add Files For Initial Commit");
            init();
        }

        public Collection<VirtualFile> getSelectedFiles() {
            return myFileList.getIncludedChanges();
        }

        @RequiredUIAccess
        @Override
        public JComponent getPreferredFocusedComponent() {
            return myFileList.getComponent();
        }

        private JComponent createToolbar() {
            ActionGroup group = createToolbarActions();
            ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, group, true);
            return toolbar.getComponent();
        }

        @Nonnull
        private ActionGroup createToolbarActions() {
            return ActionGroup.of(myFileList.getTreeActions());
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel treePanel = new JPanel(new BorderLayout());
            treePanel.add(createToolbar(), BorderLayout.NORTH);
            treePanel.add(myFileList.getComponent(), BorderLayout.CENTER);

            CommitMessageFactory messageFactory = myProject.getInstance(CommitMessageFactory.class);

            myCommitMessagePanel = messageFactory.create();
            myCommitMessagePanel.setCommitMessage("Initial commit");

            Splitter splitter = new Splitter(true);
            splitter.setHonorComponentsMinimumSize(true);
            splitter.setFirstComponent(treePanel);
            splitter.setSecondComponent(myCommitMessagePanel.getComponent());
            splitter.setProportion(0.7f);

            return splitter;
        }

        @Nonnull
        public String getCommitMessage() {
            return myCommitMessagePanel.getCommitMessage();
        }

        @Override
        public void calcData(Key<?> key, DataSink sink) {
            if (key == VcsDataKeys.COMMIT_MESSAGE_CONTROL) {
                sink.put(VcsDataKeys.COMMIT_MESSAGE_CONTROL, myCommitMessagePanel);
            }
        }

        @Override
        protected String getDimensionServiceKey() {
            return "Github.UntrackedFilesDialog";
        }
    }

    private static class GithubInfo {
        @Nonnull
        private final GithubUserDetailed myUser;
        @Nonnull
        private final GithubAuthData myAuthData;
        @Nonnull
        private final HashSet<String> myRepositoryNames;

        GithubInfo(
            @Nonnull GithubAuthData auth,
            @Nonnull GithubUserDetailed user,
            @Nonnull HashSet<String> repositoryNames
        ) {
            myUser = user;
            myAuthData = auth;
            myRepositoryNames = repositoryNames;
        }

        @Nonnull
        public GithubUserDetailed getUser() {
            return myUser;
        }

        @Nonnull
        public GithubAuthData getAuthData() {
            return myAuthData;
        }

        @Nonnull
        public HashSet<String> getRepositoryNames() {
            return myRepositoryNames;
        }
    }
}
