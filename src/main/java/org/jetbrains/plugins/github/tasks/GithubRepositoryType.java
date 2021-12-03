package org.jetbrains.plugins.github.tasks;

import com.intellij.openapi.project.Project;
import com.intellij.tasks.TaskRepository;
import com.intellij.tasks.config.TaskRepositoryEditor;
import com.intellij.tasks.impl.BaseRepositoryType;
import com.intellij.util.Consumer;
import consulo.github.icon.GitHubIconGroup;
import consulo.ui.image.Image;

import javax.annotation.Nonnull;

/**
 * @author Dennis.Ushakov
 */
public class GithubRepositoryType extends BaseRepositoryType<GithubRepository>
{

	@Nonnull
	@Override
	public String getName()
	{
		return "GitHub";
	}

	@Nonnull
	@Override
	public Image getIcon()
	{
		return GitHubIconGroup.github_icon();
	}

	@Nonnull
	@Override
	public TaskRepository createRepository()
	{
		return new GithubRepository(this);
	}

	@Override
	public Class<GithubRepository> getRepositoryClass()
	{
		return GithubRepository.class;
	}

	@Nonnull
	@Override
	public TaskRepositoryEditor createEditor(GithubRepository repository,
			Project project,
			Consumer<GithubRepository> changeListener)
	{
		return new GithubRepositoryEditor(project, repository, changeListener);
	}
}
