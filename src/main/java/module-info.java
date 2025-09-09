/**
 * @author VISTALL
 * @since 2023-04-29
 */
open module org.jetbrains.plugins.github {
    requires consulo.ide.api;
    requires com.intellij.git;

    requires commons.httpclient;

    requires com.google.gson;

    // TODO [VISTALL] remove in future
    requires java.desktop;
    requires forms.rt;
}