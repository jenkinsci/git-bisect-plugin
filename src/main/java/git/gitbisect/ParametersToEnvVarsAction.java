package git.gitbisect;

import java.util.HashMap;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.EnvironmentContributingAction;

public class ParametersToEnvVarsAction implements EnvironmentContributingAction {

	private HashMap<String, String> bisectParameters;

	public ParametersToEnvVarsAction(HashMap<String, String> bisectParameters)
	{
		this.bisectParameters = bisectParameters;
	}
	
	@Override
	public String getIconFileName() { return null; }

	@Override
	public String getDisplayName() { return null; }

	@Override
	public String getUrlName() { return null; }

	@Override
	public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars env) {
		for (String envVar : bisectParameters.keySet()) {
			env.put(envVar, bisectParameters.get(envVar));
		}
	}

}
