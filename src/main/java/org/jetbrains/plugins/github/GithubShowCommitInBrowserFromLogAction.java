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

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionParentRef;
import consulo.annotation.component.ActionRef;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.versionControlSystem.log.VcsFullCommitDetails;
import consulo.versionControlSystem.log.VcsLog;
import git4idea.GitUtil;
import git4idea.repo.GitRepository;
import org.jetbrains.plugins.github.util.GithubUtil;

import jakarta.annotation.Nonnull;

import java.util.List;

@ActionImpl(
    id = "Github.Open.Commit.In.Browser",
    parents = {
        @ActionParentRef(@ActionRef(id = "Git.Log.ContextMenu"))
    }
)
public class GithubShowCommitInBrowserFromLogAction extends GithubShowCommitInBrowserAction {
    @Override
    @RequiredUIAccess
    public void update(@Nonnull AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        VcsLog log = e.getData(VcsLog.KEY);
        if (project == null || log == null) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        List<VcsFullCommitDetails> commits = log.getSelectedDetails();
        if (commits.size() != 1) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        GitRepository repository = GitUtil.getRepositoryManager(project).getRepositoryForRoot(commits.get(0).getRoot());
        e.getPresentation().setEnabledAndVisible(repository != null && GithubUtil.isRepositoryOnGitHub(repository));
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(@Nonnull AnActionEvent e) {
        Project project = e.getRequiredData(Project.KEY);
        VcsFullCommitDetails commit = e.getRequiredData(VcsLog.KEY).getSelectedDetails().get(0);
        GitRepository repository = GitUtil.getRepositoryManager(project).getRepositoryForRoot(commit.getRoot());
        openInBrowser(project, repository, commit.getId().asString());
    }
}
