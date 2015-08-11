package jenkins.plugins.slack;

public class StubSlackService implements SlackService {

    public boolean publish(String message) {
        return true;
    }

    public boolean publish(String message, String color) {
        return true;
    }
}
