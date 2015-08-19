package jenkins.plugins.slack;


import hudson.EnvVars;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.User;
import hudson.model.Hudson;
import hudson.model.Run;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.triggers.SCMTrigger;
import hudson.util.LogTaskListener;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@SuppressWarnings("rawtypes")
public class ActiveNotifier implements FineGrainedNotifier {

    private static final Logger logger = Logger.getLogger(SlackListener.class.getName());

    SlackNotifier notifier;
    BuildListener listener;

    public ActiveNotifier(SlackNotifier notifier, BuildListener listener) {
        super();
        this.notifier = notifier;
        this.listener = listener;
    }

    private SlackService getSlack(AbstractBuild r) {
        AbstractProject<?, ?> project = r.getProject();
        String projectRoom = Util.fixEmpty(project.getProperty(SlackNotifier.SlackJobProperty.class).getRoom());
        String teamDomain = Util.fixEmpty(project.getProperty(SlackNotifier.SlackJobProperty.class).getTeamDomain());
        String token = Util.fixEmpty(project.getProperty(SlackNotifier.SlackJobProperty.class).getToken());
        String directMessage = Util.fixEmpty(project.getProperty(SlackNotifier.SlackJobProperty.class).getSendDirectMessage());

        EnvVars env = new EnvVars();
        try {
            env = r.getEnvironment(listener);
        } catch (Exception e) {
            listener.getLogger().println("Error retrieving environment vars: " + e.getMessage());
        }
        // Get the job configuration here, or if null, defer to the global configuration
        teamDomain = ObjectUtils.defaultIfNull(env.expand(teamDomain), notifier.getTeamDomain()).toString();
        token = ObjectUtils.defaultIfNull(env.expand(token), notifier.getAuthToken()).toString();
        projectRoom = ObjectUtils.defaultIfNull(env.expand(projectRoom), notifier.getRoom()).toString();

        // Support for direct messaging. These steps can be null if the build was a downstream trigger, which is okay
        String slackUsername = "";
        Cause.UserIdCause cause = (Cause.UserIdCause) r.getCause(Cause.UserIdCause.class);
        if (cause != null) {
            User user = User.get(cause.getUserId());
            if (user != null) {
                SlackNotifier.SlackUserProperty property = user.getProperty(SlackNotifier.SlackUserProperty.class);
                if (property != null) {
                    slackUsername = property.getUsername();
                }
            }
        }

        // Make a note if the build is trying to send direct messages but can't - but otherwise continue as normal
        if ((directMessage.equals("user") || directMessage.equals("both")) && slackUsername.isEmpty()) {
            logger.severe("The build is set to send direct messages, but no username was found (triggered?)");
            // If we're *only* sending direct messages, stub in an empty slack service
            if (directMessage.equals("user")) {
                return new StubSlackService();
            }
        }

        // Append the user to the normal channel list. If no job *or* global channel is set, it will default to
        // the service hook's channel as defined here: https://slack.com/services
        if (directMessage.equals("user")) {
            projectRoom = String.format("@%s", slackUsername);
        } else if (directMessage.equals("both") && ! slackUsername.isEmpty()) {
            projectRoom = String.format("%s,@%s", projectRoom, slackUsername);
        }

        logger.finer(String.format("Slack user: %s, Slack directMessage: %s, Slack room(s): %s", slackUsername, directMessage, projectRoom));

        return new StandardSlackService(teamDomain, token, projectRoom);
    }

    public void deleted(AbstractBuild r) {
    }

    public void started(AbstractBuild build) {
        
        AbstractProject<?, ?> project = build.getProject();
        SlackNotifier.SlackJobProperty jobProperty = project.getProperty(SlackNotifier.SlackJobProperty.class);

        CauseAction causeAction = build.getAction(CauseAction.class);

        if (causeAction != null) {
            Cause scmCause = causeAction.findCause(SCMTrigger.SCMTriggerCause.class);
            if (scmCause == null) {
                MessageBuilder message = new MessageBuilder(notifier, build);
                message.append(causeAction.getShortDescription());
                notifyStart(build, message.appendOpenLink().toString());
            }
        }

        String changes = getChanges(build);
        if (changes != null) {
            notifyStart(build, changes);
        } else {
            notifyStart(build, getBuildStatusMessage(build, false, jobProperty.includeCustomMessage()));
        }
    }

    private void notifyStart(AbstractBuild build, String message) {
        AbstractProject<?, ?> project = build.getProject();
        AbstractBuild<?, ?> previousBuild = project.getLastBuild().getPreviousCompletedBuild();
        if (previousBuild == null) {
            getSlack(build).publish(message, "good");
        } else {
            getSlack(build).publish(message, getBuildColor(previousBuild));
        }
    }

    public void finalized(AbstractBuild r) {
    }

    public void completed(AbstractBuild r) {
        AbstractProject<?, ?> project = r.getProject();
        SlackNotifier.SlackJobProperty jobProperty = project.getProperty(SlackNotifier.SlackJobProperty.class);
        if (jobProperty == null) {
            logger.warning("Project " + project.getName() + " has no Slack configuration.");
            return;
        }
        Result result = r.getResult();
        AbstractBuild<?, ?> previousBuild = project.getLastBuild();
        do {
            previousBuild = previousBuild.getPreviousCompletedBuild();
        } while (previousBuild != null && previousBuild.getResult() == Result.ABORTED);
        Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
        if ((result == Result.ABORTED && jobProperty.getNotifyAborted())
                || (result == Result.FAILURE
                && (previousResult != Result.FAILURE || jobProperty.getNotifyRepeatedFailure())
                && jobProperty.getNotifyFailure())
                || (result == Result.NOT_BUILT && jobProperty.getNotifyNotBuilt())
                || (result == Result.SUCCESS
                && (previousResult == Result.FAILURE || previousResult == Result.UNSTABLE)
                && jobProperty.getNotifyBackToNormal())
                || (result == Result.SUCCESS && jobProperty.getNotifySuccess())
                || (result == Result.UNSTABLE && jobProperty.getNotifyUnstable())) {
            getSlack(r).publish(getBuildStatusMessage(r, jobProperty.includeTestSummary(),
                            jobProperty.includeCustomMessage()),
                    getBuildColor(r));
            if (jobProperty.getShowCommitList()) {
                getSlack(r).publish(getCommitList(r), getBuildColor(r));
            }
        }
    }

    String getChanges(AbstractBuild r) {
        if (!r.hasChangeSetComputed()) {
            logger.finer("No change set computed...");
            return null;
        }
        ChangeLogSet changeSet = r.getChangeSet();
        List<Entry> entries = new LinkedList<Entry>();
        Set<AffectedFile> files = new HashSet<AffectedFile>();
        for (Object o : changeSet.getItems()) {
            Entry entry = (Entry) o;
            logger.finer("Entry " + o);
            entries.add(entry);
            files.addAll(entry.getAffectedFiles());
        }
        if (entries.isEmpty()) {
            logger.finer("Empty change...");
            return null;
        }
        Set<String> authors = new HashSet<String>();
        for (Entry entry : entries) {
            authors.add(entry.getAuthor().getDisplayName());
        }
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.append("Started by changes from ");
        message.append(StringUtils.join(authors, ", "));
        message.append(" (");
        message.append(files.size());
        message.append(" file(s) changed)");
        return message.appendOpenLink().toString();
    }

    String getCommitList(AbstractBuild r) {
        ChangeLogSet changeSet = r.getChangeSet();
        List<Entry> entries = new LinkedList<Entry>();
        for (Object o : changeSet.getItems()) {
            Entry entry = (Entry) o;
            logger.finer("Entry " + o);
            entries.add(entry);
        }
        if (entries.isEmpty()) {
            logger.finer("Empty change...");
            Cause.UpstreamCause c = (Cause.UpstreamCause)r.getCause(Cause.UpstreamCause.class);
            if (c == null) {
                return "No Changes.";
            }
            String upProjectName = c.getUpstreamProject();
            int buildNumber = c.getUpstreamBuild();
            AbstractProject project = Hudson.getInstance().getItemByFullName(upProjectName, AbstractProject.class);
            AbstractBuild upBuild = (AbstractBuild)project.getBuildByNumber(buildNumber);
            return getCommitList(upBuild);
        }
        Set<String> commits = new HashSet<String>();
        for (Entry entry : entries) {
            StringBuffer commit = new StringBuffer();
            commit.append(entry.getMsg());
            commit.append(" [").append(entry.getAuthor().getDisplayName()).append("]");
            commits.add(commit.toString());
        }
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.append("Changes:\n- ");
        message.append(StringUtils.join(commits, "\n- "));
        return message.toString();
    }

    static String getBuildColor(AbstractBuild r) {
        Result result = r.getResult();
        if (result == Result.SUCCESS) {
            return "good";
        } else if (result == Result.FAILURE) {
            return "danger";
        } else {
            return "warning";
        }
    }

    String getBuildStatusMessage(AbstractBuild r, boolean includeTestSummary, boolean includeCustomMessage) {
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.appendStatusMessage();
        message.appendDuration();
        message.appendOpenLink();
        if (includeTestSummary) {
            message.appendTestSummary();
        }
        if (includeCustomMessage) {
            message.appendCustomMessage();
        }
        return message.toString();
    }

    public static class MessageBuilder {

        private StringBuffer message;
        private SlackNotifier notifier;
        private AbstractBuild build;

        public MessageBuilder(SlackNotifier notifier, AbstractBuild build) {
            this.notifier = notifier;
            this.message = new StringBuffer();
            this.build = build;
            startMessage();
        }

        public MessageBuilder appendStatusMessage() {
            message.append(this.escape(getStatusMessage(build)));
            return this;
        }

        static String getStatusMessage(AbstractBuild r) {
            if (r.isBuilding()) {
                return "Starting...";
            }
            Result result = r.getResult();
            Run previousBuild = r.getProject().getLastBuild().getPreviousBuild();
            Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
            if (result == Result.SUCCESS && previousResult == Result.FAILURE) {
                return "Back to normal";
            }
            if (result == Result.FAILURE && previousResult == Result.FAILURE) {
                return "Still Failing";
            }
            if (result == Result.SUCCESS) {
                return "Success";
            }
            if (result == Result.FAILURE) {
                return "Failure";
            }
            if (result == Result.ABORTED) {
                return "Aborted";
            }
            if (result == Result.NOT_BUILT) {
                return "Not built";
            }
            if (result == Result.UNSTABLE) {
                return "Unstable";
            }
            return "Unknown";
        }

        public MessageBuilder append(String string) {
            message.append(this.escape(string));
            return this;
        }

        public MessageBuilder append(Object string) {
            message.append(this.escape(string.toString()));
            return this;
        }

        private MessageBuilder startMessage() {
            message.append(this.escape(build.getProject().getFullDisplayName()));
            message.append(" - ");
            message.append(this.escape(build.getDisplayName()));
            message.append(" ");
            return this;
        }

        public MessageBuilder appendOpenLink() {
            String url = notifier.getBuildServerUrl() + build.getUrl();
            message.append(" (<").append(url).append("|Open>)");
            return this;
        }

        public MessageBuilder appendDuration() {
            message.append(" after ");
            message.append(build.getDurationString());
            return this;
        }

        public MessageBuilder appendTestSummary() {
            AbstractTestResultAction<?> action = this.build
                    .getAction(AbstractTestResultAction.class);
            if (action != null) {
                int total = action.getTotalCount();
                int failed = action.getFailCount();
                int skipped = action.getSkipCount();
                message.append("\nTest Status:\n");
                message.append("\tPassed: " + (total - failed - skipped));
                message.append(", Failed: " + failed);
                message.append(", Skipped: " + skipped);
            } else {
                message.append("\nNo Tests found.");
            }
            return this;
        }

        public MessageBuilder appendCustomMessage() {
            AbstractProject<?, ?> project = build.getProject();
            String customMessage = Util.fixEmpty(project.getProperty(SlackNotifier.SlackJobProperty.class)
                    .getCustomMessage());
            EnvVars envVars = new EnvVars();
            try {
                envVars = build.getEnvironment(new LogTaskListener(logger, INFO));
            } catch (IOException e) {
                logger.log(SEVERE, e.getMessage(), e);
            } catch (InterruptedException e) {
                logger.log(SEVERE, e.getMessage(), e);
            }
            message.append("\n");
            message.append(envVars.expand(customMessage));
            return this;
        }

        public String escape(String string) {
            string = string.replace("&", "&amp;");
            string = string.replace("<", "&lt;");
            string = string.replace(">", "&gt;");

            return string;
        }

        public String toString() {
            return message.toString();
        }
    }
}
