/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

import consulo.annotation.component.ActionImpl;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.github.icon.GitHubIconGroup;
import consulo.github.localize.GithubLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.actions.BasicAction;
import git4idea.commands.*;
import git4idea.config.GitVcsSettings;
import git4idea.rebase.GitRebaseProblemDetector;
import git4idea.rebase.GitRebaser;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.update.GitFetchResult;
import git4idea.update.GitFetcher;
import git4idea.update.GitUpdateResult;
import git4idea.util.GitPreservingProcess;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.plugins.github.api.GithubApiUtil;
import org.jetbrains.plugins.github.api.GithubFullPath;
import org.jetbrains.plugins.github.api.GithubRepoDetailed;
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationCanceledException;
import org.jetbrains.plugins.github.util.GithubNotifications;
import org.jetbrains.plugins.github.util.GithubSettings;
import org.jetbrains.plugins.github.util.GithubUrlUtil;
import org.jetbrains.plugins.github.util.GithubUtil;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static git4idea.commands.GitLocalChangesWouldBeOverwrittenDetector.Operation;

/**
 * @author oleg
 * @since 2010-12-08
 */
@ActionImpl(id = "Github.Rebase")
public class GithubRebaseAction extends DumbAwareAction {
    private static final Logger LOG = GithubUtil.LOG;
    private static final LocalizeValue CANNOT_PERFORM_GITHUB_REBASE = LocalizeValue.localizeTODO("Can't perform github rebase");

    public GithubRebaseAction() {
        super(
            GithubLocalize.actionSyncForkText(),
            GithubLocalize.actionSyncForkDescription(),
            GitHubIconGroup.github_icon()
        );
    }

    @Override
    @RequiredUIAccess
    public void update(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        VirtualFile file = e.getData(VirtualFile.KEY);
        if (project == null || project.isDefault()) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }

        GitRepository gitRepository = GithubUtil.getGitRepository(project, file);
        if (gitRepository == null) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }

        if (!GithubUtil.isRepositoryOnGitHub(gitRepository)) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }

        e.getPresentation().setEnabledAndVisible(true);
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        VirtualFile file = e.getData(VirtualFile.KEY);

        if (project == null || project.isDisposed() || !GithubUtil.testGitExecutable(project)) {
            return;
        }

        rebaseMyGithubFork(project, file);
    }

    @RequiredUIAccess
    private static void rebaseMyGithubFork(@Nonnull final Project project, @Nullable VirtualFile file) {
        final GitRepository gitRepository = GithubUtil.getGitRepository(project, file);
        if (gitRepository == null) {
            GithubNotifications.showError(project, CANNOT_PERFORM_GITHUB_REBASE, LocalizeValue.localizeTODO("Can't find git repository"));
            return;
        }
        final VirtualFile root = gitRepository.getRoot();

        BasicAction.saveAll();

        new Task.Backgroundable(project, LocalizeValue.localizeTODO("Rebasing GitHub fork...")) {
            @Override
            @RequiredUIAccess
            public void run(@Nonnull ProgressIndicator indicator) {
                gitRepository.update();
                String upstreamRemoteUrl = GithubUtil.findUpstreamRemote(gitRepository);

                if (upstreamRemoteUrl == null) {
                    LOG.info("Configuring upstream remote");
                    indicator.setTextValue(LocalizeValue.localizeTODO("Configuring upstream remote..."));
                    upstreamRemoteUrl = configureUpstreamRemote(project, root, gitRepository, indicator);
                    if (upstreamRemoteUrl == null) {
                        return;
                    }
                }

                if (!GithubUrlUtil.isGithubUrl(upstreamRemoteUrl)) {
                    GithubNotifications.showError(
                        project,
                        CANNOT_PERFORM_GITHUB_REBASE,
                        LocalizeValue.localizeTODO("Configured upstream is not a GitHub repository: " + upstreamRemoteUrl)
                    );
                    return;
                }
                else {
                    GithubFullPath userAndRepo = GithubUrlUtil.getUserAndRepositoryFromRemoteUrl(upstreamRemoteUrl);
                    String login = GithubSettings.getInstance().getLogin();
                    if (userAndRepo != null && userAndRepo.getUser().equals(login)) {
                        GithubNotifications.showError(
                            project,
                            CANNOT_PERFORM_GITHUB_REBASE,
                            LocalizeValue.localizeTODO("Configured upstream seems to be your own " + "repository: " + upstreamRemoteUrl)
                        );
                        return;
                    }
                }

                LOG.info("Fetching upstream");
                indicator.setTextValue(LocalizeValue.localizeTODO("Fetching upstream..."));
                if (!fetchParent(project, gitRepository, indicator)) {
                    return;
                }

                LOG.info("Rebasing current branch");
                indicator.setTextValue(LocalizeValue.localizeTODO("Rebasing current branch..."));
                rebaseCurrentBranch(project, root, gitRepository, indicator);
            }
        }.queue();
    }

    @Nullable
    @RequiredUIAccess
    static String configureUpstreamRemote(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull GitRepository gitRepository,
        @Nonnull ProgressIndicator indicator
    ) {
        GithubRepoDetailed repositoryInfo = loadRepositoryInfo(project, gitRepository, indicator);
        if (repositoryInfo == null) {
            return null;
        }

        if (!repositoryInfo.isFork() || repositoryInfo.getParent() == null) {
            GithubNotifications.showWarningURL(
                project,
                CANNOT_PERFORM_GITHUB_REBASE,
                "GitHub repository ",
                "'" + repositoryInfo.getName() + "'",
                " is not a forked one",
                repositoryInfo.getHtmlUrl()
            );
            return null;
        }

        String parentRepoUrl = GithubUrlUtil.getGitHost() + '/' + repositoryInfo.getParent().getFullName() + ".git";

        LOG.info("Adding GitHub parent as a remote host");
        indicator.setTextValue(LocalizeValue.localizeTODO("Adding GitHub parent as a remote host..."));
        return addParentAsUpstreamRemote(project, root, parentRepoUrl, gitRepository);
    }

    @Nullable
    @RequiredUIAccess
    private static GithubRepoDetailed loadRepositoryInfo(
        @Nonnull Project project,
        @Nonnull GitRepository gitRepository,
        @Nonnull ProgressIndicator indicator
    ) {
        String remoteUrl = GithubUtil.findGithubRemoteUrl(gitRepository);
        if (remoteUrl == null) {
            GithubNotifications.showError(project, CANNOT_PERFORM_GITHUB_REBASE, LocalizeValue.localizeTODO("Can't find github remote"));
            return null;
        }
        GithubFullPath userAndRepo = GithubUrlUtil.getUserAndRepositoryFromRemoteUrl(remoteUrl);
        if (userAndRepo == null) {
            GithubNotifications.showError(
                project,
                CANNOT_PERFORM_GITHUB_REBASE,
                LocalizeValue.localizeTODO("Can't process remote: " + remoteUrl)
            );
            return null;
        }

        try {
            return GithubUtil.runWithValidAuth(
                project,
                indicator,
                authData -> GithubApiUtil.getDetailedRepoInfo(authData, userAndRepo.getUser(), userAndRepo.getRepository())
            );
        }
        catch (GithubAuthenticationCanceledException e) {
            return null;
        }
        catch (IOException e) {
            GithubNotifications.showError(project, LocalizeValue.localizeTODO("Can't load repository info"), e);
            return null;
        }
    }

    @Nullable
    private static String addParentAsUpstreamRemote(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull String parentRepoUrl,
        @Nonnull GitRepository gitRepository
    ) {
        GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.REMOTE);
        handler.setSilent(true);

        try {
            handler.addParameters("add", "upstream", parentRepoUrl);
            handler.run();
            if (handler.getExitCode() != 0) {
                GithubNotifications.showError(
                    project,
                    CANNOT_PERFORM_GITHUB_REBASE,
                    LocalizeValue.localizeTODO("Failed to add GitHub remote: '" + parentRepoUrl + "'. " + handler.getStderr())
                );
                return null;
            }
            // catch newly added remote
            gitRepository.update();

            return parentRepoUrl;
        }
        catch (VcsException e) {
            GithubNotifications.showError(project, CANNOT_PERFORM_GITHUB_REBASE, e);
            return null;
        }
    }

    @RequiredUIAccess
    private static boolean fetchParent(
        @Nonnull Project project,
        @Nonnull GitRepository repository,
        @Nonnull ProgressIndicator indicator
    ) {
        GitFetchResult result = new GitFetcher(project, indicator, false).fetch(repository.getRoot(), "upstream");
        if (!result.isSuccess()) {
            GitFetcher.displayFetchResult(project, result, null, result.getErrors());
            return false;
        }
        return true;
    }

    private static void rebaseCurrentBranch(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull GitRepository gitRepository,
        @Nonnull ProgressIndicator indicator
    ) {
        Git git = Git.getInstance();
        List<VirtualFile> rootsToSave = Collections.singletonList(gitRepository.getRoot());
        GitPreservingProcess process = new GitPreservingProcess(
            project,
            git,
            rootsToSave,
            "Rebasing",
            "upstream/master",
            GitVcsSettings.UpdateChangesPolicy.STASH,
            indicator,
            () -> doRebaseCurrentBranch(project, root, indicator)
        );
        process.execute();
    }

    @RequiredUIAccess
    private static void doRebaseCurrentBranch(
        @Nonnull final Project project,
        @Nonnull final VirtualFile root,
        @Nonnull ProgressIndicator indicator
    ) {
        final GitRepositoryManager repositoryManager = GitUtil.getRepositoryManager(project);

        final GitRebaser rebaser = new GitRebaser(project, Git.getInstance(), indicator);

        final GitLineHandler handler = new GitLineHandler(project, root, GitCommand.REBASE);
        handler.addParameters("upstream/master");

        final GitRebaseProblemDetector rebaseConflictDetector = new GitRebaseProblemDetector();
        handler.addLineListener(rebaseConflictDetector);

        final GitUntrackedFilesOverwrittenByOperationDetector untrackedFilesDetector =
            new GitUntrackedFilesOverwrittenByOperationDetector(root);
        final GitLocalChangesWouldBeOverwrittenDetector localChangesDetector =
            new GitLocalChangesWouldBeOverwrittenDetector(root, Operation.CHECKOUT);
        handler.addLineListener(untrackedFilesDetector);
        handler.addLineListener(localChangesDetector);
        GitTask pullTask = new GitTask(project, handler, LocalizeValue.localizeTODO("Rebasing from upstream/master"));
        pullTask.setProgressIndicator(indicator);
        pullTask.setProgressAnalyzer(new GitStandardProgressAnalyzer());
        pullTask.execute(
            true,
            false,
            new GitTaskResultHandlerAdapter() {
                @Override
                protected void onSuccess() {
                    root.refresh(false, true);
                    repositoryManager.updateRepository(root);
                    GithubNotifications.showInfo(project, "Success", "Successfully rebased GitHub fork");
                }

                @Override
                protected void onFailure() {
                    GitUpdateResult result =
                        rebaser.handleRebaseFailure(handler, root, rebaseConflictDetector, untrackedFilesDetector, localChangesDetector);
                    repositoryManager.updateRepository(root);
                    if (result == GitUpdateResult.NOTHING_TO_UPDATE
                        || result == GitUpdateResult.SUCCESS
                        || result == GitUpdateResult.SUCCESS_WITH_RESOLVED_CONFLICTS) {
                        GithubNotifications.showInfo(project, "Success", "Successfully rebased GitHub fork");
                    }
                }
            }
        );
    }
}
