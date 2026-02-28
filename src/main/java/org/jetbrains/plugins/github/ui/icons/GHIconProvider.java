package org.jetbrains.plugins.github.ui.icons;

import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.github.icon.GitHubIconGroup;
import consulo.language.icon.IconDescriptor;
import consulo.language.icon.IconDescriptorUpdater;
import consulo.language.psi.PsiDirectory;
import consulo.language.psi.PsiElement;
import jakarta.annotation.Nonnull;

/**
 * @author VISTALL
 * @since 2026-02-28
 */
@ExtensionImpl
public class GHIconProvider implements IconDescriptorUpdater {

    @RequiredReadAction
    @Override
    public void updateIcon(@Nonnull IconDescriptor iconDescriptor, @Nonnull PsiElement element, int flags) {
        if (element instanceof PsiDirectory dir && ".github".equals(dir.getName())) {
            iconDescriptor.setMainIcon(GitHubIconGroup.foldergithub());
        }
    }
}
