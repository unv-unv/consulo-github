package org.jetbrains.plugins.github.tasks;

import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.task.ui.BaseRepositoryEditor;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.FormBuilder;
import consulo.ui.ex.awt.GridBag;
import consulo.ui.ex.awt.JBLabel;
import consulo.ui.ex.awt.JBTextField;
import consulo.ui.ex.awt.event.DocumentAdapter;
import consulo.util.lang.StringUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.plugins.github.api.GithubApiUtil;
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationCanceledException;
import org.jetbrains.plugins.github.util.GithubNotifications;
import org.jetbrains.plugins.github.util.GithubUtil;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * @author Dennis.Ushakov
 */
public class GithubRepositoryEditor extends BaseRepositoryEditor<GithubRepository> {
    private MyTextField myHost;
    private MyTextField myRepoAuthor;
    private MyTextField myRepoName;
    private MyTextField myToken;
    private JButton myTokenButton;
    private JBLabel myHostLabel;
    private JBLabel myRepositoryLabel;
    private JBLabel myTokenLabel;

    public GithubRepositoryEditor(
        Project project,
        GithubRepository repository,
        Consumer<GithubRepository> changeListener
    ) {
        super(project, repository, changeListener);
        myUrlLabel.setVisible(false);
        myURLText.setVisible(false);
        myUsernameLabel.setVisible(false);
        myUserNameText.setVisible(false);
        myPasswordLabel.setVisible(false);
        myPasswordText.setVisible(false);
        myUseHttpAuthenticationCheckBox.setVisible(false);

        myHost.setText(repository.getUrl());
        myRepoAuthor.setText(repository.getRepoAuthor());
        myRepoName.setText(repository.getRepoName());
        myToken.setText(repository.getToken());

        DocumentListener buttonUpdater = new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent e) {
                updateTokenButton();
            }
        };

        myHost.getDocument().addDocumentListener(buttonUpdater);
        myRepoAuthor.getDocument().addDocumentListener(buttonUpdater);
        myRepoName.getDocument().addDocumentListener(buttonUpdater);
        myURLText.getDocument().addDocumentListener(buttonUpdater);
    }

    @Nullable
    @Override
    protected JComponent createCustomPanel() {
        myHostLabel = new JBLabel("Host:", SwingConstants.RIGHT);
        myHost = new MyTextField("Github host");

        JPanel myHostPanel = new JPanel(new BorderLayout(5, 0));
        myHostPanel.add(myHost, BorderLayout.CENTER);
        myHostPanel.add(myShareUrlCheckBox, BorderLayout.EAST);

        myRepositoryLabel = new JBLabel("Repository:", SwingConstants.RIGHT);
        myRepoAuthor = new MyTextField("Repository Owner");
        myRepoName = new MyTextField("Repository Name");
        myRepoAuthor.setPreferredSize("SomelongNickname");
        myRepoName.setPreferredSize("SomelongReponame-with-suffixes");

        JPanel myRepoPanel = new JPanel(new GridBagLayout());
        GridBag bag = new GridBag().setDefaultWeightX(1).setDefaultFill(GridBagConstraints.HORIZONTAL);
        myRepoPanel.add(myRepoAuthor, bag.nextLine().next());
        myRepoPanel.add(new JLabel("/"), bag.next().fillCellNone().insets(0, 5, 0, 5).weightx(0));
        myRepoPanel.add(myRepoName, bag.next());

        myTokenLabel = new JBLabel("API Token:", SwingConstants.RIGHT);
        myToken = new MyTextField("OAuth2 token");
        myTokenButton = new JButton("Create API token");
        myTokenButton.addActionListener(e -> {
            generateToken();
            doApply();
        });

        JPanel myTokenPanel = new JPanel();
        myTokenPanel.setLayout(new BorderLayout(5, 5));
        myTokenPanel.add(myToken, BorderLayout.CENTER);
        myTokenPanel.add(myTokenButton, BorderLayout.EAST);

        installListener(myHost);
        installListener(myRepoAuthor);
        installListener(myRepoName);
        installListener(myToken);

        return FormBuilder.createFormBuilder()
            .setAlignLabelOnRight(true)
            .addLabeledComponent(myHostLabel, myHostPanel)
            .addLabeledComponent(myRepositoryLabel, myRepoPanel)
            .addLabeledComponent(myTokenLabel, myTokenPanel)
            .getPanel();
    }

    @Override
    public void apply() {
        myRepository.setRepoName(getRepoName());
        myRepository.setRepoAuthor(getRepoAuthor());
        myRepository.setToken(getToken());
        super.apply();
    }

    @RequiredUIAccess
    private void generateToken() {
        try {
            myToken.setText(GithubUtil.computeValueInModal(
                myProject,
                "Access to GitHub",
                indicator -> GithubUtil.runWithValidBasicAuthForHost(
                    myProject,
                    indicator,
                    getHost(),
                    auth -> GithubApiUtil.getReadOnlyToken(auth, getRepoAuthor(), getRepoName(), "Intellij tasks plugin")
                )
            ));
        }
        catch (GithubAuthenticationCanceledException ignore) {
        }
        catch (IOException e) {
            GithubNotifications.showErrorDialog(myProject, LocalizeValue.localizeTODO("Can't get access token"), e);
        }
    }

    @Override
    public void setAnchor(@Nullable JComponent anchor) {
        super.setAnchor(anchor);
        myHostLabel.setAnchor(anchor);
        myRepositoryLabel.setAnchor(anchor);
        myTokenLabel.setAnchor(anchor);
    }

    private void updateTokenButton() {
        if (StringUtil.isEmptyOrSpaces(getHost()) ||
            StringUtil.isEmptyOrSpaces(getRepoAuthor()) ||
            StringUtil.isEmptyOrSpaces(getRepoName())) {
            myTokenButton.setEnabled(false);
        }
        else {
            myTokenButton.setEnabled(true);
        }
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return myHost;
    }

    @Nonnull
    private String getHost() {
        return myHost.getText().trim();
    }

    @Nonnull
    private String getRepoAuthor() {
        return myRepoAuthor.getText().trim();
    }

    @Nonnull
    private String getRepoName() {
        return myRepoName.getText().trim();
    }

    @Nonnull
    private String getToken() {
        return myToken.getText().trim();
    }

    public static class MyTextField extends JBTextField {
        private int myWidth = -1;

        public MyTextField(@Nonnull String hintCaption) {
            getEmptyText().setText(hintCaption);
        }

        public void setPreferredSize(@Nonnull String sampleSizeString) {
            myWidth = getFontMetrics(getFont()).stringWidth(sampleSizeString);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            if (myWidth != -1) {
                size.width = myWidth;
            }
            return size;
        }
    }
}
