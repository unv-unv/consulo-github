/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
import consulo.github.icon.GitHubIconGroup;
import consulo.language.editor.PlatformDataKeys;
import consulo.logging.Logger;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.Splitter;
import consulo.ui.ex.awt.TabbedPaneWrapper;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.Pair;
import consulo.util.lang.StringUtil;
import consulo.util.lang.ref.Ref;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangesBrowser;
import consulo.versionControlSystem.change.ChangesBrowserFactory;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.DialogManager;
import git4idea.GitCommit;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import git4idea.changes.GitChangeUtils;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.ui.GitCommitListPanel;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.plugins.github.api.*;
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationCanceledException;
import org.jetbrains.plugins.github.ui.GithubCreatePullRequestDialog;
import org.jetbrains.plugins.github.util.GithubAuthData;
import org.jetbrains.plugins.github.util.GithubNotifications;
import org.jetbrains.plugins.github.util.GithubUrlUtil;
import org.jetbrains.plugins.github.util.GithubUtil;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

import static org.jetbrains.plugins.github.util.GithubUtil.setVisibleEnabled;

/**
 * @author Aleksey Pivovarov
 */
public class GithubCreatePullRequestAction extends DumbAwareAction {
    private static final Logger LOG = GithubUtil.LOG;
    private static final String CANNOT_CREATE_PULL_REQUEST = "Can't create pull request";

    public GithubCreatePullRequestAction() {
        super("Create Pull Request", "Create pull request from current branch", GitHubIconGroup.github_icon());
    }

    @Override
    @RequiredUIAccess
    public void update(AnActionEvent e) {
        final Project project = e.getData(PlatformDataKeys.PROJECT);
        final VirtualFile file = e.getData(PlatformDataKeys.VIRTUAL_FILE);
        if (project == null || project.isDefault()) {
            setVisibleEnabled(e, false, false);
            return;
        }

        final GitRepository gitRepository = GithubUtil.getGitRepository(project, file);
        if (gitRepository == null) {
            setVisibleEnabled(e, false, false);
            return;
        }

        if (!GithubUtil.isRepositoryOnGitHub(gitRepository)) {
            setVisibleEnabled(e, false, false);
            return;
        }

        setVisibleEnabled(e, true, true);
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(AnActionEvent e) {
        final Project project = e.getData(PlatformDataKeys.PROJECT);
        final VirtualFile file = e.getData(PlatformDataKeys.VIRTUAL_FILE);

        if (project == null || project.isDisposed() || !GithubUtil.testGitExecutable(project)) {
            return;
        }

        createPullRequest(project, file);
    }

    @RequiredUIAccess
    static void createPullRequest(@Nonnull final Project project, @Nullable final VirtualFile file) {
        final Git git = Git.getInstance();

        final GitRepository repository = GithubUtil.getGitRepository(project, file);
        if (repository == null) {
            GithubNotifications.showError(project, CANNOT_CREATE_PULL_REQUEST, "Can't find git repository");
            return;
        }
        repository.update();

        final Pair<GitRemote, String> remote = GithubUtil.findGithubRemote(repository);
        if (remote == null) {
            GithubNotifications.showError(project, CANNOT_CREATE_PULL_REQUEST, "Can't find GitHub remote");
            return;
        }
        final String remoteUrl = remote.getSecond();
        final String remoteName = remote.getFirst().getName();
        final String puttyKey = remote.getFirst().getPuttyKeyFile();
        String upstreamUrl = GithubUtil.findUpstreamRemote(repository);
        final GithubFullPath upstreamUserAndRepo = upstreamUrl == null || !GithubUrlUtil.isGithubUrl(upstreamUrl) ?
            null : GithubUrlUtil.getUserAndRepositoryFromRemoteUrl(upstreamUrl);

        final GithubFullPath userAndRepo = GithubUrlUtil.getUserAndRepositoryFromRemoteUrl(remoteUrl);
        if (userAndRepo == null) {
            GithubNotifications.showError(project, CANNOT_CREATE_PULL_REQUEST, "Can't process remote: " + remoteUrl);
            return;
        }

        final GitLocalBranch currentBranch = repository.getCurrentBranch();
        if (currentBranch == null) {
            GithubNotifications.showError(project, CANNOT_CREATE_PULL_REQUEST, "No current branch");
            return;
        }

        final GithubInfo info = loadGithubInfoWithModal(project, userAndRepo, upstreamUserAndRepo);
        if (info == null) {
            return;
        }
        final Set<RemoteBranch> branches = getAvailableBranchesFromGit(repository);
        branches.addAll(info.getBranches());

        GithubRepo parent = info.getRepo().getParent();
        String suggestedBranch = parent == null ? null : parent.getUserName() + ":" + parent.getDefaultBranch();
        Collection<String> suggestions = ContainerUtil.map(branches, RemoteBranch::getReference);
        Consumer<String> showDiff = s -> showDiffByRef(project, s, branches, repository, currentBranch.getName());
        final GithubCreatePullRequestDialog dialog = new GithubCreatePullRequestDialog(project, suggestions, suggestedBranch, showDiff);
        DialogManager.show(dialog);
        if (!dialog.isOK()) {
            return;
        }

        new Task.Backgroundable(project, "Creating pull request...") {
            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                LOG.info("Pushing current branch");
                indicator.setText("Pushing current branch...");
                GitCommandResult result = git.push(repository, remoteName, remoteUrl, puttyKey, currentBranch.getName(), true);
                if (!result.success()) {
                    GithubNotifications.showError(project, CANNOT_CREATE_PULL_REQUEST,
                        "Push failed:<br/>" + result.getErrorOutputAsHtmlString()
                    );
                    return;
                }

                String from = info.getRepo().getUserName() + ":" + currentBranch.getName();
                String onto = dialog.getTargetBranch();
                GithubAuthData auth = info.getAuthData();

                GithubFullPath targetRepo = findTargetRepository(project, auth, onto, info.getRepo(), upstreamUserAndRepo, branches);
                if (targetRepo == null) {
                    GithubNotifications.showError(
                        project,
                        CANNOT_CREATE_PULL_REQUEST,
                        "Can't find repository for specified branch: " + onto
                    );
                    return;
                }

                LOG.info("Creating pull request");
                indicator.setText("Creating pull request...");
                GithubPullRequest request =
                    createPullRequest(project, auth, targetRepo, dialog.getRequestTitle(), dialog.getDescription(), from, onto);
                if (request == null) {
                    return;
                }

                GithubNotifications.showInfoURL(
                    project,
                    "Successfully created pull request",
                    "Pull Request #" + request.getNumber(),
                    request.getHtmlUrl()
                );
            }
        }.queue();
    }

    @Nullable
    @RequiredUIAccess
    private static GithubInfo loadGithubInfoWithModal(
        @Nonnull final Project project,
        @Nonnull final GithubFullPath userAndRepo,
        @Nullable final GithubFullPath upstreamUserAndRepo
    ) {
        try {
            return GithubUtil.computeValueInModal(project, "Access to GitHub", indicator -> {
                final Ref<GithubRepoDetailed> reposRef = new Ref<>();
                final GithubAuthData auth = GithubUtil.runAndGetValidAuth(
                    project,
                    indicator,
                    authData -> reposRef.set(GithubApiUtil.getDetailedRepoInfo(
                        authData,
                        userAndRepo.getUser(),
                        userAndRepo.getRepository()
                    ))
                );
                List<RemoteBranch> branches = loadAvailableBranchesFromGithub(project, auth, reposRef.get(), upstreamUserAndRepo);
                return new GithubInfo(auth, reposRef.get(), branches);
            });
        }
        catch (GithubAuthenticationCanceledException e) {
            return null;
        }
        catch (IOException e) {
            GithubNotifications.showErrorDialog(project, CANNOT_CREATE_PULL_REQUEST, e);
            return null;
        }
    }

    @Nullable
    private static GithubFullPath findTargetRepository(
        @Nonnull Project project,
        @Nonnull GithubAuthData auth,
        @Nonnull String onto,
        @Nonnull GithubRepoDetailed repo,
        @Nullable GithubFullPath upstreamPath,
        @Nonnull Collection<RemoteBranch> branches
    ) {
        String targetUser = onto.substring(0, onto.indexOf(':'));
        @Nullable GithubRepo parent = repo.getParent();
        @Nullable GithubRepo source = repo.getSource();

        for (RemoteBranch branch : branches) {
            if (StringUtil.equalsIgnoreCase(targetUser, branch.getUser()) && branch.getRepo() != null) {
                return new GithubFullPath(branch.getUser(), branch.getRepo());
            }
        }

        if (isRepoOwner(targetUser, repo)) {
            return repo.getFullPath();
        }
        if (parent != null && isRepoOwner(targetUser, parent)) {
            return parent.getFullPath();
        }
        if (source != null && isRepoOwner(targetUser, source)) {
            return source.getFullPath();
        }
        if (upstreamPath != null && StringUtil.equalsIgnoreCase(targetUser, upstreamPath.getUser())) {
            return upstreamPath;
        }
        if (source != null) {
            try {
                GithubRepoDetailed target = GithubApiUtil.getDetailedRepoInfo(auth, targetUser, repo.getName());
                if (target.getSource() != null
                    && StringUtil.equalsIgnoreCase(target.getSource().getUserName(), source.getUserName())) {
                    return target.getFullPath();
                }
            }
            catch (IOException ignore) {
            }

            try {
                GithubRepo fork = GithubApiUtil.findForkByUser(auth, source.getUserName(), source.getName(), targetUser);
                if (fork != null) {
                    return fork.getFullPath();
                }
            }
            catch (IOException e) {
                GithubNotifications.showError(project, CANNOT_CREATE_PULL_REQUEST, e);
            }
        }

        return null;
    }

    private static boolean isRepoOwner(@Nonnull String user, @Nonnull GithubRepo repo) {
        return StringUtil.equalsIgnoreCase(user, repo.getUserName());
    }

    @Nullable
    private static GithubPullRequest createPullRequest(
        @Nonnull Project project,
        @Nonnull GithubAuthData auth,
        @Nonnull GithubFullPath targetRepo,
        @Nonnull String title,
        @Nonnull String description,
        @Nonnull String from,
        @Nonnull String onto
    ) {
        try {
            return GithubApiUtil.createPullRequest(auth, targetRepo.getUser(), targetRepo.getRepository(), title, description, from, onto);
        }
        catch (IOException e) {
            GithubNotifications.showError(project, CANNOT_CREATE_PULL_REQUEST, e);
            return null;
        }
    }

    @Nonnull
    private static Set<RemoteBranch> getAvailableBranchesFromGit(@Nonnull GitRepository gitRepository) {
        Set<RemoteBranch> result = new HashSet<>();
        for (GitRemoteBranch remoteBranch : gitRepository.getBranches().getRemoteBranches()) {
            for (String url : remoteBranch.getRemote().getUrls()) {
                if (GithubUrlUtil.isGithubUrl(url)) {
                    GithubFullPath path = GithubUrlUtil.getUserAndRepositoryFromRemoteUrl(url);
                    if (path != null) {
                        result.add(new RemoteBranch(path.getUser(), remoteBranch.getNameForRemoteOperations(),
                            path.getRepository(), remoteBranch.getNameForLocalOperations()
                        ));
                        break;
                    }
                }
            }
        }
        return result;
    }

    @Nonnull
    private static List<RemoteBranch> loadAvailableBranchesFromGithub(
        @Nonnull final Project project,
        @Nonnull final GithubAuthData auth,
        @Nonnull final GithubRepoDetailed repo,
        @Nullable final GithubFullPath upstreamPath
    ) {
        List<RemoteBranch> result = new ArrayList<>();
        try {
            final GithubRepo parent = repo.getParent();
            final GithubRepo source = repo.getSource();

            if (parent != null) {
                result.addAll(getBranches(auth, parent.getUserName(), parent.getName()));
            }

            result.addAll(getBranches(auth, repo.getUserName(), repo.getName()));

            if (source != null && !equals(source, parent)) {
                result.addAll(getBranches(auth, source.getUserName(), source.getName()));
            }

            if (upstreamPath != null && !equals(upstreamPath, repo)
                && !equals(upstreamPath, parent)
                && !equals(upstreamPath, source)) {
                result.addAll(getBranches(auth, upstreamPath.getUser(), upstreamPath.getRepository()));
            }
        }
        catch (IOException e) {
            GithubNotifications.showError(project, "Can't load available branches", e);
        }
        return result;
    }

    @Nonnull
    private static List<RemoteBranch> getBranches(
        @Nonnull GithubAuthData auth,
        @Nonnull final String user,
        @Nonnull final String repo
    ) throws IOException {
        List<GithubBranch> branches = GithubApiUtil.getRepoBranches(auth, user, repo);
        return ContainerUtil.map(branches, branch -> new RemoteBranch(user, branch.getName(), repo));
    }

    private static boolean equals(@Nonnull GithubRepo repo1, @Nullable GithubRepo repo2) {
        return repo2 != null && StringUtil.equalsIgnoreCase(repo1.getUserName(), repo2.getUserName());
    }

    private static boolean equals(@Nonnull GithubFullPath repo1, @Nullable GithubRepo repo2) {
        return repo2 != null && StringUtil.equalsIgnoreCase(repo1.getUser(), repo2.getUserName());
    }

    @RequiredUIAccess
    private static void showDiffByRef(
        @Nonnull Project project,
        @Nullable String ref,
        @Nonnull Set<RemoteBranch> branches,
        @Nonnull GitRepository gitRepository,
        @Nonnull String currentBranch
    ) {
        RemoteBranch branch = findRemoteBranch(branches, ref);
        if (branch == null || branch.getLocalBranch() == null) {
            GithubNotifications.showErrorDialog(project, "Can't show diff", "Can't find local branch");
            return;
        }
        String targetBranch = branch.getLocalBranch();

        DiffInfo info = getDiffInfo(project, gitRepository, currentBranch, targetBranch);
        if (info == null) {
            GithubNotifications.showErrorDialog(project, "Can't show diff", "Can't get diff info");
            return;
        }

        GithubCreatePullRequestDiffDialog dialog = new GithubCreatePullRequestDiffDialog(project, info);
        dialog.show();
    }

    @Nullable
    private static RemoteBranch findRemoteBranch(@Nonnull Set<RemoteBranch> branches, @Nullable String ref) {
        if (ref == null) {
            return null;
        }
        List<String> list = StringUtil.split(ref, ":");
        if (list.size() != 2) {
            return null;
        }
        for (RemoteBranch branch : branches) {
            if (StringUtil.equalsIgnoreCase(list.get(0), branch.getUser())
                && StringUtil.equals(list.get(1), branch.getBranch())) {
                return branch;
            }
        }

        return null;
    }

    @Nullable
    private static DiffInfo getDiffInfo(
        @Nonnull final Project project,
        @Nonnull final GitRepository repository,
        @Nonnull final String currentBranch,
        @Nonnull final String targetBranch
    ) {
        try {
            return GithubUtil.computeValueInModal(project, "Access to Git", indicator -> {
                List<GitCommit> commits = GitHistoryUtils.history(project, repository.getRoot(), targetBranch + "..");
                Collection<Change> diff =
                    GitChangeUtils.getDiff(repository.getProject(), repository.getRoot(), targetBranch, currentBranch, null);
                return new DiffInfo(targetBranch, currentBranch, commits, diff);
            });
        }
        catch (VcsException e) {
            LOG.info(e);
            return null;
        }
    }

    private static class GithubCreatePullRequestDiffDialog extends DialogWrapper {
        @Nonnull
        private final Project myProject;
        @Nonnull
        private final DiffInfo myInfo;
        private JPanel myLogPanel;

        public GithubCreatePullRequestDiffDialog(@Nonnull Project project, @Nonnull DiffInfo info) {
            super(project, false);
            myProject = project;
            myInfo = info;
            setTitle(String.format("Comparing %s with %s", info.getFrom(), info.getTo()));
            setModal(false);
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            myLogPanel = new GithubCreatePullRequestLogPanel(myProject, myInfo);
            JPanel diffPanel = new GithubCreatePullRequestDiffPanel(myProject, myInfo);

            TabbedPaneWrapper wrapper = new TabbedPaneWrapper(getDisposable());
            wrapper.addTab("Log", PlatformIconGroup.vcsBranch(), myLogPanel, null);
            wrapper.addTab("Diff", PlatformIconGroup.actionsDiff(), diffPanel, null);
            return wrapper.getComponent();
        }

        @Nonnull
        @Override
        protected Action[] createActions() {
            return new Action[0];
        }

        @Override
        protected String getDimensionServiceKey() {
            return "Github.CreatePullRequestDiffDialog";
        }
    }

    private static class GithubCreatePullRequestDiffPanel extends JPanel {
        private final Project myProject;
        private final DiffInfo myInfo;

        public GithubCreatePullRequestDiffPanel(@Nonnull Project project, @Nonnull DiffInfo info) {
            super(new BorderLayout(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
            myProject = project;
            myInfo = info;

            add(createCenterPanel());
        }

        private JComponent createCenterPanel() {
            List<Change> diff = new ArrayList<>(myInfo.getDiff());
            ChangesBrowserFactory browserFactory = Application.get().getInstance(ChangesBrowserFactory.class);

            final ChangesBrowser changesBrowser =
                browserFactory.createChangeBrowser(myProject, null, diff, null, false, true, null, ChangesBrowser.MyUseCase.COMMITTED_CHANGES, null);
            changesBrowser.setChangesToDisplay(diff);
            return changesBrowser.getComponent();
        }
    }

    private static class GithubCreatePullRequestLogPanel extends JPanel {
        private final Project myProject;
        private final DiffInfo myInfo;

        private GitCommitListPanel myCommitPanel;

        GithubCreatePullRequestLogPanel(@Nonnull Project project, @Nonnull DiffInfo info) {
            super(new BorderLayout(UIUtil.DEFAULT_HGAP, UIUtil.DEFAULT_VGAP));
            myProject = project;
            myInfo = info;

            add(createCenterPanel());
        }

        private JComponent createCenterPanel() {
            ChangesBrowserFactory browserFactory = Application.get().getInstance(ChangesBrowserFactory.class);
            final ChangesBrowser<Change> changesBrowser = browserFactory.createChangeBrowser(
                myProject,
                null,
                Collections.emptyList(),
                null,
                false,
                true,
                null,
                ChangesBrowser.MyUseCase.COMMITTED_CHANGES,
                null
            );

            myCommitPanel = new GitCommitListPanel(
                myInfo.getCommits(),
                String.format("Branch %s is fully merged to %s", myInfo.getFrom(), myInfo.getTo())
            );
            addSelectionListener(myCommitPanel, changesBrowser);

            myCommitPanel.registerDiffAction(changesBrowser.getDiffAction());

            Splitter rootPanel = new Splitter(false, 0.7f);
            rootPanel.setSecondComponent(changesBrowser.getComponent());
            rootPanel.setFirstComponent(myCommitPanel);

            return rootPanel;
        }

        private static void addSelectionListener(
            @Nonnull GitCommitListPanel sourcePanel,
            @Nonnull final ChangesBrowser<Change> changesBrowser
        ) {
            sourcePanel.addListSelectionListener(commit -> changesBrowser.setChangesToDisplay(new ArrayList<>(commit.getChanges())));
        }
    }

    private static class RemoteBranch {
        @Nonnull
        final String myUser;
        @Nonnull
        final String myBranch;

        @Nullable
        final String myRepo;
        @Nullable
        final String myLocalBranch;

        private RemoteBranch(@Nonnull String user, @Nonnull String branch) {
            this(user, branch, null, null);
        }

        private RemoteBranch(@Nonnull String user, @Nonnull String branch, @Nonnull String repo) {
            this(user, branch, repo, null);
        }

        public RemoteBranch(
            @Nonnull String user,
            @Nonnull String branch,
            @Nullable String repo,
            @Nullable String localBranch
        ) {
            myUser = user;
            myBranch = branch;
            myRepo = repo;
            myLocalBranch = localBranch;
        }

        @Nonnull
        public String getReference() {
            return myUser + ":" + myBranch;
        }

        @Nonnull
        public String getUser() {
            return myUser;
        }

        @Nonnull
        public String getBranch() {
            return myBranch;
        }

        @Nullable
        public String getRepo() {
            return myRepo;
        }

        @Nullable
        public String getLocalBranch() {
            return myLocalBranch;
        }

        @Override
        public boolean equals(Object o) {
            return this == o
                || o instanceof RemoteBranch that
                && StringUtil.equalsIgnoreCase(myUser, that.myUser)
                && StringUtil.equalsIgnoreCase(myBranch, that.myBranch);
        }

        @Override
        public int hashCode() {
            int result = myUser.hashCode();
            result = 31 * result + myBranch.hashCode();
            return result;
        }
    }

    private static class GithubInfo {
        @Nonnull
        private final GithubRepoDetailed myRepo;
        @Nonnull
        private final GithubAuthData myAuthData;
        @Nonnull
        private final List<RemoteBranch> myBranches;

        private GithubInfo(
            @Nonnull GithubAuthData authData,
            @Nonnull GithubRepoDetailed repo,
            @Nonnull List<RemoteBranch> branches
        ) {
            myAuthData = authData;
            myRepo = repo;
            myBranches = branches;
        }

        @Nonnull
        public GithubRepoDetailed getRepo() {
            return myRepo;
        }

        @Nonnull
        public GithubAuthData getAuthData() {
            return myAuthData;
        }

        @Nonnull
        public List<RemoteBranch> getBranches() {
            return myBranches;
        }
    }

    private static class DiffInfo {
        @Nonnull
        private final List<GitCommit> commits;
        @Nonnull
        private final Collection<Change> diff;
        @Nonnull
        private final String from;
        @Nonnull
        private final String to;

        private DiffInfo(
            @Nonnull String from,
            @Nonnull String to,
            @Nonnull List<GitCommit> commits,
            @Nonnull Collection<Change> diff
        ) {
            this.commits = commits;
            this.diff = diff;
            this.from = from;
            this.to = to;
        }

        @Nonnull
        public List<GitCommit> getCommits() {
            return commits;
        }

        @Nonnull
        public Collection<Change> getDiff() {
            return diff;
        }

        @Nonnull
        public String getFrom() {
            return from;
        }

        @Nonnull
        public String getTo() {
            return to;
        }
    }
}
