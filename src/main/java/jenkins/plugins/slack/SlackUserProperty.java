package jenkins.plugins.slack;

import hudson.Extension;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.model.UserPropertyDescriptor;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

import java.util.logging.Logger;

public class SlackUserProperty extends UserProperty {

    private static final Logger logger = Logger.getLogger(SlackNotifier.class.getName());

    private String username;

    @DataBoundConstructor
    public SlackUserProperty(String username) {
        this.username = username;
    }

    @Exported
    public User getUser() {
        return user;
    }

    @Exported
    public String getUsername() {
        return username;
    }

    @Extension
    public static class DescriptorImpl extends UserPropertyDescriptor {

        public DescriptorImpl() { super(SlackUserProperty.class); }

        @Override
        public String getDisplayName() {
            return "Slack Username";
        }

        @Override
        public SlackUserProperty newInstance(StaplerRequest sr, JSONObject formData) throws hudson.model.Descriptor.FormException {
            return new SlackUserProperty(sr.getParameter("slackUsername"));
        }

        @Override
        public UserProperty newInstance(User arg0) {
            return null;
        }
    }
}