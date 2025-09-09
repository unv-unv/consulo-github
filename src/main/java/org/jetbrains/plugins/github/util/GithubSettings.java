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
package org.jetbrains.plugins.github.util;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.component.persist.PersistentStateComponent;
import consulo.component.persist.State;
import consulo.component.persist.Storage;
import consulo.component.persist.StoragePathMacros;
import consulo.credentialStorage.PasswordSafe;
import consulo.logging.Logger;
import consulo.util.lang.StringUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import org.jetbrains.plugins.github.api.GithubApiUtil;

import java.util.ArrayList;
import java.util.Collection;

import static org.jetbrains.plugins.github.util.GithubAuthData.AuthType;

/**
 * @author oleg
 */
@Singleton
@SuppressWarnings("MethodMayBeStatic")
@State(
    name = "GithubSettings",
    storages = {@Storage(file = StoragePathMacros.APP_CONFIG + "/github_settings.xml")}
)
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
public class GithubSettings implements PersistentStateComponent<GithubSettings.State> {
    private static final Logger LOG = GithubUtil.LOG;
    private static final String GITHUB_SETTINGS_PASSWORD_KEY = "GITHUB_SETTINGS_PASSWORD_KEY";

    private State myState = new State();

    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(State state) {
        myState = state;
    }

    public static class State {
        @Nullable
        public String LOGIN = null;
        @Nonnull
        public String HOST = GithubApiUtil.DEFAULT_GITHUB_HOST;
        @Nonnull
        public AuthType AUTH_TYPE = AuthType.ANONYMOUS;
        public boolean ANONYMOUS_GIST = false;
        public boolean OPEN_IN_BROWSER_GIST = true;
        public boolean PRIVATE_GIST = true;
        public boolean SAVE_PASSWORD = true;
        @Nonnull
        public Collection<String> TRUSTED_HOSTS = new ArrayList<>();
        @Nullable
        public String CREATE_PULL_REQUEST_DEFAULT_BRANCH = null;
        public boolean VALID_GIT_AUTH = true;

    }

    public static GithubSettings getInstance() {
        return Application.get().getInstance(GithubSettings.class);
    }

    @Nonnull
    public String getHost() {
        return myState.HOST;
    }

    @Nullable
    public String getLogin() {
        return myState.LOGIN;
    }

    @Nonnull
    public AuthType getAuthType() {
        return myState.AUTH_TYPE;
    }

    public boolean isAuthConfigured() {
        return !myState.AUTH_TYPE.equals(AuthType.ANONYMOUS);
    }

    private void setHost(@Nonnull String host) {
        myState.HOST = StringUtil.notNullize(host, GithubApiUtil.DEFAULT_GITHUB_HOST);
    }

    private void setLogin(@Nullable String login) {
        myState.LOGIN = login;
    }

    private void setAuthType(@Nonnull AuthType authType) {
        myState.AUTH_TYPE = authType;
    }

    public boolean isAnonymousGist() {
        return myState.ANONYMOUS_GIST;
    }

    public boolean isOpenInBrowserGist() {
        return myState.OPEN_IN_BROWSER_GIST;
    }

    public boolean isPrivateGist() {
        return myState.PRIVATE_GIST;
    }

    public boolean isValidGitAuth() {
        return myState.VALID_GIT_AUTH;
    }

    public boolean isSavePassword() {
        return myState.SAVE_PASSWORD;
    }

    public boolean isSavePasswordMakesSense() {
        return PasswordSafe.getInstance().isMemoryOnly();
    }

    @Nullable
    public String getCreatePullRequestDefaultBranch() {
        return myState.CREATE_PULL_REQUEST_DEFAULT_BRANCH;
    }

    public void setAnonymousGist(final boolean anonymousGist) {
        myState.ANONYMOUS_GIST = anonymousGist;
    }

    public void setPrivateGist(final boolean privateGist) {
        myState.PRIVATE_GIST = privateGist;
    }

    public void setSavePassword(final boolean savePassword) {
        myState.SAVE_PASSWORD = savePassword;
    }

    public void setOpenInBrowserGist(final boolean openInBrowserGist) {
        myState.OPEN_IN_BROWSER_GIST = openInBrowserGist;
    }

    public void setCreatePullRequestDefaultBranch(@Nonnull String branch) {
        myState.CREATE_PULL_REQUEST_DEFAULT_BRANCH = branch;
    }

    public void setValidGitAuth(final boolean validGitAuth) {
        myState.VALID_GIT_AUTH = validGitAuth;
    }

    @Nonnull
    public Collection<String> getTrustedHosts() {
        return myState.TRUSTED_HOSTS;
    }

    public void addTrustedHost(String host) {
        if (!myState.TRUSTED_HOSTS.contains(host)) {
            myState.TRUSTED_HOSTS.add(host);
        }
    }

    @Nonnull
    private String getPassword() {
        String password = PasswordSafe.getInstance().getPassword(null, GithubSettings.class, GITHUB_SETTINGS_PASSWORD_KEY);
        return StringUtil.notNullize(password);
    }

    private void setPassword(@Nonnull String password, boolean rememberPassword) {
        PasswordSafe passwordSafe = PasswordSafe.getInstance();

        if (rememberPassword) {
            passwordSafe.storePassword(null, GithubSettings.class, GITHUB_SETTINGS_PASSWORD_KEY, password);
        }
    }

    @Nonnull
    public GithubAuthData getAuthData() {
        switch (getAuthType()) {
            case BASIC:
                return GithubAuthData.createBasicAuth(getHost(), getLogin(), getPassword());
            case TOKEN:
                return GithubAuthData.createTokenAuth(getHost(), getPassword());
            case ANONYMOUS:
                return GithubAuthData.createAnonymous();
            default:
                throw new IllegalStateException("GithubSettings: getAuthData - wrong AuthType: " + getAuthType());
        }
    }

    private void setAuthData(@Nonnull GithubAuthData auth, boolean rememberPassword) {
        setValidGitAuth(true);
        setAuthType(auth.getAuthType());

        switch (auth.getAuthType()) {
            case BASIC:
                assert auth.getBasicAuth() != null;
                setLogin(auth.getBasicAuth().getLogin());
                setPassword(auth.getBasicAuth().getPassword(), rememberPassword);
                break;
            case TOKEN:
                assert auth.getTokenAuth() != null;
                setLogin(null);
                setPassword(auth.getTokenAuth().getToken(), rememberPassword);
                break;
            case ANONYMOUS:
                setLogin(null);
                setPassword("", rememberPassword);
                break;
            default:
                throw new IllegalStateException("GithubSettings: setAuthData - wrong AuthType: " + getAuthType());
        }
    }

    public void setCredentials(@Nonnull String host, @Nonnull GithubAuthData auth, boolean rememberPassword) {
        setHost(host);
        setAuthData(auth, rememberPassword);
    }
}