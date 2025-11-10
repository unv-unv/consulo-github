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
package org.jetbrains.plugins.github.util;


import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import consulo.versionControlSystem.VcsNotifier;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.awt.*;

import static org.jetbrains.plugins.github.util.GithubUtil.getErrorTextFromException;

public class GithubNotifications {
    private static final Logger LOG = GithubUtil.LOG;

    public static void showInfo(@Nonnull Project project, @Nonnull String title, @Nonnull String message) {
        LOG.info(title + "; " + message);
        VcsNotifier.getInstance(project).notifyImportantInfo(title, message);
    }

    public static void showWarning(@Nonnull Project project, @Nonnull LocalizeValue title, @Nonnull LocalizeValue message) {
        LOG.info(title + "; " + message);
        VcsNotifier.getInstance(project).notifyImportantWarning(title.get(), message.get());
    }

    public static void showError(@Nonnull Project project, @Nonnull LocalizeValue title, @Nonnull LocalizeValue message) {
        LOG.info(title + "; " + message);
        VcsNotifier.getInstance(project).notifyError(title.get(), message.get());
    }

    public static void showError(
        @Nonnull Project project,
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue message,
        @Nonnull String logDetails
    ) {
        LOG.warn(title + "; " + message + "; " + logDetails);
        VcsNotifier.getInstance(project).notifyError(title.get(), message.get());
    }

    public static void showError(@Nonnull Project project, @Nonnull LocalizeValue title, @Nonnull Exception e) {
        LOG.warn(title + "; ", e);
        VcsNotifier.getInstance(project).notifyError(title.get(), getErrorTextFromException(e));
    }

    public static void showInfoURL(
        @Nonnull Project project,
        @Nonnull LocalizeValue title,
        @Nonnull String message,
        @Nonnull String url
    ) {
        LOG.info(title + "; " + message + "; " + url);
        VcsNotifier.getInstance(project).notifyImportantInfo(
            title.get(),
            "<a href='" + url + "'>" + message + "</a>",
            NotificationListener.URL_OPENING_LISTENER
        );
    }

    public static void showWarningURL(
        @Nonnull Project project,
        @Nonnull LocalizeValue title,
        @Nonnull String prefix,
        @Nonnull String highlight,
        @Nonnull String postfix,
        @Nonnull String url
    ) {
        LOG.info(title + "; " + prefix + highlight + postfix + "; " + url);
        VcsNotifier.getInstance(project).notifyImportantWarning(
            title.get(),
            prefix + "<a href='" + url + "'>" + highlight + "</a>" + postfix,
            NotificationListener.URL_OPENING_LISTENER
        );
    }

    public static void showErrorURL(
        @Nonnull Project project,
        @Nonnull LocalizeValue title,
        @Nonnull String prefix,
        @Nonnull String highlight,
        @Nonnull String postfix,
        @Nonnull String url
    ) {
        LOG.info(title + "; " + prefix + highlight + postfix + "; " + url);
        VcsNotifier.getInstance(project).notifyError(title.get(), prefix + "<a href='" + url + "'>" + highlight + "</a>" +
            postfix, NotificationListener.URL_OPENING_LISTENER);
    }

    @RequiredUIAccess
    public static void showInfoDialog(
        @Nullable Project project,
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue message
    ) {
        LOG.info(title + "; " + message);
        Messages.showInfoMessage(project, message.get(), title.get());
    }

    @RequiredUIAccess
    public static void showInfoDialog(
        @Nonnull Component component,
        @Nonnull String title,
        @Nonnull String message
    ) {
        LOG.info(title + "; " + message);
        Messages.showInfoMessage(component, message, title);
    }

    @RequiredUIAccess
    public static void showWarningDialog(
        @Nullable Project project,
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue message
    ) {
        LOG.info(title + "; " + message);
        Messages.showWarningDialog(project, message.get(), title.get());
    }

    @RequiredUIAccess
    public static void showWarningDialog(
        @Nonnull Component component,
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue message
    ) {
        LOG.info(title + "; " + message);
        Messages.showWarningDialog(component, message.get(), title.get());
    }

    @RequiredUIAccess
    public static void showErrorDialog(
        @Nullable Project project,
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue message
    ) {
        LOG.info(title + "; " + message);
        Messages.showErrorDialog(project, message.get(), title.get());
    }

    @RequiredUIAccess
    public static void showErrorDialog(
        @Nullable Project project,
        @Nonnull LocalizeValue title,
        @Nonnull Exception e
    ) {
        LOG.warn(title.get(), e);
        Messages.showErrorDialog(project, getErrorTextFromException(e), title.get());
    }

    @RequiredUIAccess
    public static void showErrorDialog(
        @Nonnull Component component,
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue message
    ) {
        LOG.info(title + "; " + message);
        Messages.showErrorDialog(component, message.get(), title.get());
    }

    @RequiredUIAccess
    public static void showErrorDialog(
        @Nonnull Component component,
        @Nonnull String title,
        @Nonnull Exception e
    ) {
        LOG.info(title, e);
        Messages.showInfoMessage(component, getErrorTextFromException(e), title);
    }

    @RequiredUIAccess
    public static int showYesNoDialog(
        @Nullable Project project,
        @Nonnull String title,
        @Nonnull String message
    ) {
        return Messages.showYesNoDialog(project, message, title, UIUtil.getQuestionIcon());
    }
}
