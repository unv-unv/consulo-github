/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package org.jetbrains.plugins.github.extensions;

import consulo.annotation.component.ExtensionImpl;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.lang.function.ThrowableFunction;
import consulo.versionControlSystem.checkout.CheckoutProvider;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.actions.BasicAction;
import git4idea.checkout.GitCheckoutProvider;
import git4idea.checkout.GitCloneDialog;
import git4idea.commands.Git;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.plugins.github.api.GithubApiUtil;
import org.jetbrains.plugins.github.api.GithubRepo;
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationCanceledException;
import org.jetbrains.plugins.github.util.GithubAuthData;
import org.jetbrains.plugins.github.util.GithubNotifications;
import org.jetbrains.plugins.github.util.GithubUtil;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * @author oleg
 */
@ExtensionImpl
public class GithubCheckoutProvider implements CheckoutProvider {
    @Override
    @RequiredUIAccess
    public void doCheckout(@Nonnull final Project project, @Nullable final Listener listener) {
        BasicAction.saveAll();

        List<GithubRepo> availableRepos;
        try {
            availableRepos = GithubUtil.computeValueInModal(
                project,
                "Access to GitHub",
                indicator -> GithubUtil.runWithValidAuth(
                    project,
                    indicator,
                    (ThrowableFunction<GithubAuthData, List<GithubRepo>, IOException>)authData -> GithubApiUtil.getAvailableRepos(authData)
                )
            );
        }
        catch (GithubAuthenticationCanceledException e) {
            return;
        }
        catch (IOException e) {
            GithubNotifications.showError(project, "Couldn't get the list of GitHub repositories", e);
            return;
        }
        Collections.sort(
            availableRepos,
            (r1, r2) -> {
                final int comparedOwners = r1.getUserName().compareTo(r2.getUserName());
                return comparedOwners != 0 ? comparedOwners : r1.getName().compareTo(r2.getName());
            }
        );

        final GitCloneDialog dialog = new GitCloneDialog(project);
        // Add predefined repositories to history
        dialog.prependToHistory("-----------------------------------------------");
        for (int i = availableRepos.size() - 1; i >= 0; i--) {
            dialog.prependToHistory(availableRepos.get(i).getCloneUrl());
        }
        dialog.show();
        if (!dialog.isOK()) {
            return;
        }
        dialog.rememberSettings();
        final VirtualFile destinationParent = LocalFileSystem.getInstance()
            .findFileByIoFile(new File(dialog.getParentDirectory()));
        if (destinationParent == null) {
            return;
        }
        final String sourceRepositoryURL = dialog.getSourceRepositoryURL();
        final String directoryName = dialog.getDirectoryName();
        final String parentDirectory = dialog.getParentDirectory();
        final String puttyKey = dialog.getPuttyKeyFile();

        Git git = Git.getInstance();
        GitCheckoutProvider.clone(
            project,
            git,
            listener,
            destinationParent,
            sourceRepositoryURL,
            directoryName,
            parentDirectory,
            puttyKey
        );
    }

    @Override
    public String getVcsName() {
        return "Git_Hub";
    }
}
