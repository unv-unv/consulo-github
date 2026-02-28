package org.jetbrains.plugins.github.tasks;

import consulo.annotation.component.ExtensionImpl;
import consulo.github.icon.GitHubIconGroup;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.task.BaseRepositoryType;
import consulo.task.TaskRepository;
import consulo.task.ui.TaskRepositoryEditor;
import consulo.ui.image.Image;

import jakarta.annotation.Nonnull;
import java.util.function.Consumer;

/**
 * @author Dennis.Ushakov
 */
@ExtensionImpl
public class GithubRepositoryType extends BaseRepositoryType<GithubRepository> {
    @Nonnull
    @Override
    public String getId() {
        return "github";
    }

    @Nonnull
    @Override
    public LocalizeValue getPresentableName() {
        return LocalizeValue.localizeTODO("GitHub");
    }

    @Nonnull
    @Override
    public Image getIcon() {
        return GitHubIconGroup.github();
    }

    @Nonnull
    @Override
    public TaskRepository createRepository() {
        return new GithubRepository(this);
    }

    @Override
    public Class<GithubRepository> getRepositoryClass() {
        return GithubRepository.class;
    }

    @Nonnull
    @Override
    public TaskRepositoryEditor createEditor(
        GithubRepository repository,
        Project project,
        Consumer<GithubRepository> changeListener
    ) {
        return new GithubRepositoryEditor(project, repository, changeListener);
    }
}
