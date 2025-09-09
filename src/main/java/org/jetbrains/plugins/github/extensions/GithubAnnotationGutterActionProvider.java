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
package org.jetbrains.plugins.github.extensions;

import consulo.annotation.component.ExtensionImpl;
import consulo.ui.ex.action.AnAction;
import consulo.versionControlSystem.annotate.AnnotationGutterActionProvider;
import consulo.versionControlSystem.annotate.FileAnnotation;
import jakarta.annotation.Nonnull;
import org.jetbrains.plugins.github.GithubShowCommitInBrowserFromAnnotateAction;

/**
 * @author Kirill Likhodedov
 */
@ExtensionImpl
public class GithubAnnotationGutterActionProvider implements AnnotationGutterActionProvider {
    @Nonnull
    @Override
    public AnAction createAction(@Nonnull FileAnnotation annotation) {
        return new GithubShowCommitInBrowserFromAnnotateAction(annotation);
    }
}
