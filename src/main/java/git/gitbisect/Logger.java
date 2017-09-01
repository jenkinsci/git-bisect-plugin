package git.gitbisect;

import hudson.model.TaskListener;

public class Logger {
	private static TaskListener listener;
	
	public static void initializeLogger(TaskListener listener)
	{
		Logger.listener = listener;
	}
	
	public static void log(String line)
	{
		listener.getLogger().println("[GIT-BISECT]: " + line);
	}

	public static void error(String string) {
		listener.error(string);
	}
}
