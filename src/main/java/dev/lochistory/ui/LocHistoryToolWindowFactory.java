package dev.lochistory.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

public final class LocHistoryToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LocHistoryPanel panel = new LocHistoryPanel(project);
        Content content = toolWindow.getContentManager().getFactory().createContent(panel, "Overview", false);
        toolWindow.getContentManager().addContent(content);
    }
}
