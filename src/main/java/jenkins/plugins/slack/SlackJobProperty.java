package jenkins.plugins.slack;

import hudson.Extension;
import hudson.model.*;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

public class SlackJobProperty extends JobProperty<AbstractProject<?, ?>> {

    private static final Logger logger = Logger.getLogger(SlackNotifier.class.getName());

    private String teamDomain;
    private String token;
    private String room;
    private String sendDirectMessage;
    private boolean startNotification;
    private boolean notifySuccess;
    private boolean notifyAborted;
    private boolean notifyNotBuilt;
    private boolean notifyUnstable;
    private boolean notifyFailure;
    private boolean notifyBackToNormal;
    private boolean notifyRepeatedFailure;
    private boolean includeTestSummary;
    private boolean showCommitList;
    private boolean includeCustomMessage;
    private String customMessage;

    @DataBoundConstructor
    public SlackJobProperty(String teamDomain,
                            String token,
                            String room,
                            String sendDirectMessage,
                            boolean startNotification,
                            boolean notifyAborted,
                            boolean notifyFailure,
                            boolean notifyNotBuilt,
                            boolean notifySuccess,
                            boolean notifyUnstable,
                            boolean notifyBackToNormal,
                            boolean notifyRepeatedFailure,
                            boolean includeTestSummary,
                            boolean showCommitList,
                            boolean includeCustomMessage,
                            String customMessage) {
        this.teamDomain = teamDomain;
        this.token = token;
        this.room = room;
        this.sendDirectMessage = sendDirectMessage;
        this.startNotification = startNotification;
        this.notifyAborted = notifyAborted;
        this.notifyFailure = notifyFailure;
        this.notifyNotBuilt = notifyNotBuilt;
        this.notifySuccess = notifySuccess;
        this.notifyUnstable = notifyUnstable;
        this.notifyBackToNormal = notifyBackToNormal;
        this.notifyRepeatedFailure = notifyRepeatedFailure;
        this.includeTestSummary = includeTestSummary;
        this.showCommitList = showCommitList;
        this.includeCustomMessage = includeCustomMessage;
        this.customMessage = customMessage;
    }

    @Exported
    public String getTeamDomain() {
        return teamDomain;
    }

    @Exported
    public String getToken() {
        return token;
    }

    @Exported
    public String getRoom() { return room; }

    @Exported
    public String getSendDirectMessage() { return sendDirectMessage; }

    @Exported
    public boolean getStartNotification() {
        return startNotification;
    }

    @Exported
    public boolean getNotifySuccess() {
        return notifySuccess;
    }

    @Exported
    public boolean getShowCommitList() {
        return showCommitList;
    }

    @Override
    public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener) {
        if (startNotification) {
            Map<Descriptor<Publisher>, Publisher> map = build.getProject().getPublishersList().toMap();
            for (Publisher publisher : map.values()) {
                if (publisher instanceof SlackNotifier) {
                    logger.log(Level.FINER, "Invoking Started...");
                    ((SlackNotifier) publisher).update();
                    new ActiveNotifier((SlackNotifier) publisher, listener).started(build);
                }
            }
        }
        return super.prebuild(build, listener);
    }

    @Exported
    public boolean getNotifyAborted() {
        return notifyAborted;
    }

    @Exported
    public boolean getNotifyFailure() {
        return notifyFailure;
    }

    @Exported
    public boolean getNotifyNotBuilt() {
        return notifyNotBuilt;
    }

    @Exported
    public boolean getNotifyUnstable() {
        return notifyUnstable;
    }

    @Exported
    public boolean getNotifyBackToNormal() {
        return notifyBackToNormal;
    }

    @Exported
    public boolean includeTestSummary() {
        return includeTestSummary;
    }

    @Exported
    public boolean getNotifyRepeatedFailure() {
        return notifyRepeatedFailure;
    }

    @Exported
    public boolean includeCustomMessage() {
        return includeCustomMessage;
    }

    @Exported
    public String getCustomMessage() {
        return customMessage;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {

        public String getDisplayName() {
            return "Slack Notifications";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return true;
        }

        @Override
        public SlackJobProperty newInstance(StaplerRequest sr, JSONObject formData) throws hudson.model.Descriptor.FormException {
            return new SlackJobProperty(
                    sr.getParameter("slackTeamDomain"),
                    sr.getParameter("slackToken"),
                    sr.getParameter("slackProjectRoom"),
                    sr.getParameter("slackSendDirectMessage"),
                    sr.getParameter("slackStartNotification") != null,
                    sr.getParameter("slackNotifyAborted") != null,
                    sr.getParameter("slackNotifyFailure") != null,
                    sr.getParameter("slackNotifyNotBuilt") != null,
                    sr.getParameter("slackNotifySuccess") != null,
                    sr.getParameter("slackNotifyUnstable") != null,
                    sr.getParameter("slackNotifyBackToNormal") != null,
                    sr.getParameter("slackNotifyRepeatedFailure") != null,
                    sr.getParameter("includeTestSummary") != null,
                    sr.getParameter("slackShowCommitList") != null,
                    sr.getParameter("includeCustomMessage") != null,
                    sr.getParameter("customMessage"));
        }

        public FormValidation doTestConnection(@QueryParameter("slackTeamDomain") final String teamDomain,
                                               @QueryParameter("slackToken") final String authToken,
                                               @QueryParameter("slackProjectRoom") final String room) throws FormException {
            try {
                SlackService testSlackService = new StandardSlackService(teamDomain, authToken, room);
                String message = "Slack/Jenkins plugin: you're all set.";
                boolean success = testSlackService.publish(message, "green");
                return success ? FormValidation.ok("Success") : FormValidation.error("Failure");
            } catch (Exception e) {
                return FormValidation.error("Client error : " + e.getMessage());
            }
        }
    }
}