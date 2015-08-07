package jenkins.plugins.slack;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.User;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import jenkins.model.JenkinsLocationConfiguration;

public class SlackNotifier extends Notifier {

    private static final Logger logger = Logger.getLogger(SlackNotifier.class.getName());

    private String teamDomain;
    private String authToken;
    private String buildServerUrl;
    private String room;
    private String sendAs;

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public String getTeamDomain() {
        return teamDomain;
    }

    public String getRoom() {
        return room;
    }

    public String getAuthToken() {
        return authToken;
    }

    public String getBuildServerUrl() {
        if(buildServerUrl == null || buildServerUrl == "") {
            JenkinsLocationConfiguration jenkinsConfig = new JenkinsLocationConfiguration();
            return jenkinsConfig.getUrl();
        }
        else {
            return buildServerUrl;
        }
    }

    public String getSendAs() {
        return sendAs;
    }

    @DataBoundConstructor
    public SlackNotifier(final String teamDomain, final String authToken, final String room, String buildServerUrl, final String sendAs) {
        super();
        this.teamDomain = teamDomain;
        this.authToken = authToken;
        this.buildServerUrl = buildServerUrl;
        this.room = room;
        this.sendAs = sendAs;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    public SlackService newSlackService(String teamDomain, String token, String projectRoom, String directMessage) {
        // Settings are passed here from the job, if they are null, use global settings
        if (teamDomain == null) {
            teamDomain = getTeamDomain();
        }
        if (token == null) {
            token = getAuthToken();
        }
        if (projectRoom == null) {
            projectRoom = getRoom();
        }

        // Support for direct messaging
        String username = System.getProperty("user.name");
        User user = User.get(username);
        username = user.getProperty(SlackUserProperty.class).getUsername();

        // Append the user to the normal channel list
        if (directMessage.equals("user")) {
            projectRoom = String.format("@%s", username);
        } else if (directMessage.equals("both")) {
            projectRoom = (projectRoom.isEmpty())
                    ? String.format("@%s", username)
                    : String.format("%s,@%s", projectRoom, username);
        }

        logger.log(Level.FINER, String.format("Slack user: %s, Slack directMessage: %s, Slack room(s): %s", username, directMessage, projectRoom));

        return new StandardSlackService(teamDomain, token, projectRoom);
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        return true;
    }

    public void update() {
        this.teamDomain = getDescriptor().teamDomain;
        this.authToken = getDescriptor().token;
        this.buildServerUrl = getDescriptor().buildServerUrl;
        this.room = getDescriptor().room;
        this.sendAs = getDescriptor().sendAs;
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String teamDomain;
        private String token;
        private String room;
        private String buildServerUrl;
        private String sendAs;

        public DescriptorImpl() {
            load();
        }

        public String getTeamDomain() {
            return teamDomain;
        }

        public String getToken() {
            return token;
        }

        public String getRoom() {
            return room;
        }

        public String getBuildServerUrl() {
            if(buildServerUrl == null || buildServerUrl == "") {
                JenkinsLocationConfiguration jenkinsConfig = new JenkinsLocationConfiguration();
                return jenkinsConfig.getUrl();
            }
            else {
                return buildServerUrl;
            }
        }

        public String getSendAs() {
            return sendAs;
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public SlackNotifier newInstance(StaplerRequest sr) {
            if (teamDomain == null) {
                teamDomain = sr.getParameter("slackTeamDomain");
            }
            if (token == null) {
                token = sr.getParameter("slackToken");
            }
            if (buildServerUrl == null) {
                buildServerUrl = sr.getParameter("slackBuildServerUrl");
            }
            if (room == null) {
                room = sr.getParameter("slackRoom");
            }
            if (sendAs == null) {
                sendAs = sr.getParameter("slackSendAs");
            }
            return new SlackNotifier(teamDomain, token, room, buildServerUrl, sendAs);
        }

        @Override
        public boolean configure(StaplerRequest sr, JSONObject formData) throws FormException {
            teamDomain = sr.getParameter("slackTeamDomain");
            token = sr.getParameter("slackToken");
            room = sr.getParameter("slackRoom");
            buildServerUrl = sr.getParameter("slackBuildServerUrl");
            sendAs = sr.getParameter("slackSendAs");
            if(buildServerUrl == null || buildServerUrl == "") {
                JenkinsLocationConfiguration jenkinsConfig = new JenkinsLocationConfiguration();
                buildServerUrl = jenkinsConfig.getUrl();
            }
            if (buildServerUrl != null && !buildServerUrl.endsWith("/")) {
                buildServerUrl = buildServerUrl + "/";
            }
            save();
            return super.configure(sr, formData);
        }

        SlackService getSlackService(final String teamDomain, final String authToken, final String room) {
            return new StandardSlackService(teamDomain, authToken, room);
        }

        @Override
        public String getDisplayName() {
            return "Slack Notifications";
        }

        public FormValidation doTestConnection(@QueryParameter("slackTeamDomain") final String teamDomain,
                                               @QueryParameter("slackToken") final String authToken,
                                               @QueryParameter("slackRoom") final String room,
                                               @QueryParameter("slackBuildServerUrl") final String buildServerUrl) throws FormException {
            try {
                SlackService testSlackService = getSlackService(teamDomain, authToken, room);
                String message = "Slack/Jenkins plugin: you're all set on " + buildServerUrl;
                boolean success = testSlackService.publish(message, "green");
                return success ? FormValidation.ok("Success") : FormValidation.error("Failure");
            } catch (Exception e) {
                return FormValidation.error("Client error : " + e.getMessage());
            }
        }
    }
}
