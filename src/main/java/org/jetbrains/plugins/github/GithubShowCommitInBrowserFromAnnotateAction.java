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

import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.editor.PlatformDataKeys;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.versionControlSystem.UpToDateLineNumberProvider;
import consulo.versionControlSystem.action.LineNumberListener;
import consulo.versionControlSystem.annotate.FileAnnotation;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.plugins.github.util.GithubUtil;

/**
 * @author Kirill Likhodedov
 */
public class GithubShowCommitInBrowserFromAnnotateAction extends GithubShowCommitInBrowserAction implements LineNumberListener {
    private final FileAnnotation myAnnotation;
    private int myLineNumber = -1;

    public GithubShowCommitInBrowserFromAnnotateAction(FileAnnotation annotation) {
        super();
        myAnnotation = annotation;
    }

    @Override
    @RequiredUIAccess
    public void update(@Nonnull AnActionEvent e) {
        EventData eventData = calcData(e, myLineNumber);
        if (eventData == null) {
            e.getPresentation().setEnabled(false);
            e.getPresentation().setVisible(false);
            return;
        }
        int corrected = eventData.getCorrectedLineNumber();
        e.getPresentation().setEnabled(corrected >= 0 && myAnnotation.getLineRevisionNumber(corrected) != null);
        e.getPresentation().setVisible(GithubUtil.isRepositoryOnGitHub(eventData.getRepository()));
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(@Nonnull AnActionEvent e) {
        EventData eventData = calcData(e, myLineNumber);
        if (eventData == null) {
            return;
        }

        final VcsRevisionNumber revisionNumber = myAnnotation.getLineRevisionNumber(eventData.getCorrectedLineNumber
            ());
        if (revisionNumber != null) {
            openInBrowser(eventData.getProject(), eventData.getRepository(), revisionNumber.asString());
        }
    }

    @Nullable
    private static EventData calcData(AnActionEvent e, int lineNumber) {
        Project project = e.getData(PlatformDataKeys.PROJECT);
        VirtualFile virtualFile = e.getData(PlatformDataKeys.VIRTUAL_FILE);
        if (project == null || virtualFile == null) {
            return null;
        }
        Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
        if (document == null) {
            return null;
        }
        final UpToDateLineNumberProvider myGetUpToDateLineNumber = UpToDateLineNumberProvider.of(
            document,
            project
        );
        int corrected = myGetUpToDateLineNumber.getLineNumber(lineNumber);

        GitRepository repository = GitUtil.getRepositoryManager(project).getRepositoryForFile(virtualFile);
        if (repository == null) {
            return null;
        }

        return new EventData(project, repository, corrected);
    }

    @Override
    public void accept(int integer) {
        myLineNumber = integer;
    }

    private static class EventData {
        @Nonnull
        private final Project myProject;
        @Nonnull
        private final GitRepository myRepository;
        private final int myCorrectedLineNumber;

        private EventData(@Nonnull Project project, @Nonnull GitRepository repository, int correctedLineNumber) {
            myProject = project;
            myRepository = repository;
            myCorrectedLineNumber = correctedLineNumber;
        }

        @Nonnull
        public Project getProject() {
            return myProject;
        }

        @Nonnull
        public GitRepository getRepository() {
            return myRepository;
        }

        private int getCorrectedLineNumber() {
            return myCorrectedLineNumber;
        }
    }
}
