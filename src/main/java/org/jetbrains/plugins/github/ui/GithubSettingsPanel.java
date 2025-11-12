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
package org.jetbrains.plugins.github.ui;

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.platform.Platform;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.HyperlinkLabel;
import consulo.ui.ex.awt.JBLabel;
import consulo.ui.ex.awt.event.DocumentAdapter;
import consulo.util.lang.Comparing;
import org.jetbrains.plugins.github.api.GithubUser;
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationException;
import org.jetbrains.plugins.github.util.GithubAuthData;
import org.jetbrains.plugins.github.util.GithubNotifications;
import org.jetbrains.plugins.github.util.GithubSettings;
import org.jetbrains.plugins.github.util.GithubUtil;

import jakarta.annotation.Nonnull;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;
import java.io.IOException;

/**
 * @author oleg
 * @since 2010-10-20
 */
public class GithubSettingsPanel {
    private static final String DEFAULT_PASSWORD_TEXT = "************";

    private static final Logger LOG = GithubUtil.LOG;

    private final GithubSettings mySettings;

    private JPasswordField myTokenField;
    private JPanel myPane;
    private JButton myTestButton;
    private JTextField myHostTextField;
    private JButton myGetTokenButton;
    private JPanel myInfoPanel;

    private boolean myCredentialsModified;

    public GithubSettingsPanel(@Nonnull GithubSettings settings) {
        mySettings = settings;
        myInfoPanel.add(new JBLabel("Do not have an account at github.com?"));
        HyperlinkLabel signUpLink = new HyperlinkLabel("Sign up");
        signUpLink.addHyperlinkListener(e -> Platform.current().openInBrowser("https://" + myHostTextField.getText() + "/signup"));
        myInfoPanel.add(signUpLink);

        myGetTokenButton.addActionListener(e -> {
            String url = "https://" + myHostTextField.getText() + "/settings/tokens/new?description=Consulo%20GitHub%20integration%20plugin&scopes=repo%2Cgist%2Cread%3Aorg%2Cworkflow%2Cread%3Auser%2Cuser%3Aemail";

            Platform.current().openInBrowser(url);
        });
        
        myTestButton.addActionListener(e -> new Task.Modal(null, LocalizeValue.localizeTODO("Checking..."), myPane, false) {
                private GithubUser myUser;
                private Exception myError;

                @Override
                public void run(@Nonnull ProgressIndicator progressIndicator) {
                    try {
                        myUser = GithubUtil.checkAuthData(getAuthData());
                    }
                    catch (Exception e1) {
                        myError = e1;
                    }
                }

                @Override
                @RequiredUIAccess
                public void onSuccess() {
                    if (myUser != null) {
                        if (GithubAuthData.AuthType.TOKEN.equals(getAuthType())) {
                            GithubNotifications.showInfoDialog(myPane, "Success", "Connection successful for user " + myUser.getLogin());
                        }
                        else {
                            GithubNotifications.showInfoDialog(myPane, "Success", "Connection successful");
                        }
                    } else if (myError instanceof GithubAuthenticationException ex) {
                        GithubNotifications.showErrorDialog(
                            myPane,
                            LocalizeValue.localizeTODO("Login Failure"),
                            LocalizeValue.localizeTODO("Can't login using given credentials: " + ex.getMessage())
                        );
                    } else if (myError instanceof IOException ioEx) {
                        LOG.warn(ioEx);

                        GithubNotifications.showErrorDialog(
                            myPane,
                            LocalizeValue.localizeTODO("Login Failure"),
                            LocalizeValue.localizeTODO("Can't login: " + GithubUtil.getErrorTextFromException(ioEx))
                        );
                    }
                }
            }.queue());

        myTokenField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent documentEvent) {
                myCredentialsModified = true;
            }
        });

        DocumentListener passwordEraser = new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent e) {
                if (!myCredentialsModified) {
                    erasePassword();
                }
            }
        };
        myHostTextField.getDocument().addDocumentListener(passwordEraser);

        reset();
    }

    private void erasePassword() {
        myCredentialsModified = true;
    }

    public JComponent getPanel() {
        return myPane;
    }

    @Nonnull
    public String getHost() {
        return myHostTextField.getText().trim();
    }

    public void setHost(@Nonnull String host) {
        myHostTextField.setText(host);
    }

    @Nonnull
    public GithubAuthData.AuthType getAuthType() {
        return GithubAuthData.AuthType.TOKEN;
    }

    @Nonnull
    public GithubAuthData getAuthData() {
        if (!myCredentialsModified) {
            return mySettings.getAuthData();
        }
        return GithubAuthData.createTokenAuth(getHost(), new String(myTokenField.getPassword()));
    }

    public void reset() {
        setHost(mySettings.getHost());
        myTokenField.setText(DEFAULT_PASSWORD_TEXT);

        myCredentialsModified = false;
    }

    public boolean isModified() {
        return !Comparing.equal(mySettings.getHost(), getHost()) || myCredentialsModified;
    }

    public void resetCredentialsModification() {
        myCredentialsModified = false;
    }

    private void createUIComponents() {
        Document doc = new PlainDocument();
        myTokenField = new JPasswordField(doc, null, 0);
    }
}
