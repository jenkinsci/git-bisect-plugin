package git.bisect;

import java.util.HashMap;
import java.util.Map.Entry;

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
		for (Entry<String, String> envVar : bisectParameters.entrySet()) {
			env.put(envVar.getKey(), envVar.getValue());
		}
	}

}
