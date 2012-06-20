package com.attask.jenkins.templates;

import com.attask.jenkins.UnixUtils;
import com.google.common.collect.ImmutableMap;
import hudson.Extension;
import hudson.Launcher;
import hudson.XmlFile;
import hudson.model.*;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import java.io.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * User: Joel Johnson
 * Date: 6/18/12
 * Time: 8:30 PM
 */
@ExportedBean
public class ImplementationBuildWrapper extends BuildWrapper {
	private final String templateName;
	private final String implementationName;
	private final String variables;
	private boolean synced;

	@DataBoundConstructor
	public ImplementationBuildWrapper(String templateName, String implementationName, String variables) {
		this.templateName = templateName;
		this.implementationName = implementationName;
		this.variables = variables;
		this.synced = false;
	}

	public void sync() throws IOException {
		AbstractProject template = null;
		AbstractProject implementation = null;
		try {
			template = Project.findNearest(templateName);
			implementation = Project.findNearest(implementationName);
		} catch (NullPointerException ignore) {
			//unfortunately, on jenkins load we get null pointer exceptions here
		}
		if(template != null && implementation != null) {
			syncFromTemplate(template, implementation);
		}
	}

	static void syncFromTemplate(AbstractProject template, AbstractProject implementation) throws IOException {
		if(
				implementation == null ||
				!(implementation instanceof BuildableItemWithBuildWrappers) ||
				!(implementation instanceof Describable) ||
				template == null ||
				!(template instanceof BuildableItemWithBuildWrappers) ||
				!(template instanceof Describable)
			) {
			return;
		}

		ImplementationBuildWrapper implementationBuildWrapper = getBuildWrapperFromImplementation(implementation);
		if(implementationBuildWrapper == null) {
			return;
		}

		Map<Pattern, String> propertiesMap = getPropertiesMap(template, implementation, implementationBuildWrapper);
		XmlFile implementationXmlFile = replaceConfig(template, implementation, propertiesMap);
		refreshAndSave(template, implementation, implementationBuildWrapper, implementationXmlFile);
	}

	private static ImplementationBuildWrapper getBuildWrapperFromImplementation(AbstractProject implementation) {
		ImplementationBuildWrapper implementationBuildWrapper = null;
		DescribableList<BuildWrapper,Descriptor<BuildWrapper>> buildWrappersList = ((BuildableItemWithBuildWrappers) implementation).getBuildWrappersList();
		for (BuildWrapper wrapper : buildWrappersList) {
			if(wrapper instanceof ImplementationBuildWrapper) {
				implementationBuildWrapper = (ImplementationBuildWrapper)wrapper;
			}
		}
		return implementationBuildWrapper;
	}

	private static Map<Pattern, String> getPropertiesMap(AbstractProject template, AbstractProject implementation, ImplementationBuildWrapper implementationBuildWrapper) {
		Map<String, String> variables = expandToMap(implementationBuildWrapper.getVariables());

		ImmutableMap.Builder<Pattern, String> patternPairsBuilder = ImmutableMap.builder();
		patternPairsBuilder.put(Pattern.compile(template.getClass().getCanonicalName() + ">"), implementation.getClass().getCanonicalName() + ">");

		for (Map.Entry<String, String> variable : variables.entrySet()) {
			patternPairsBuilder.put(Pattern.compile("\\$\\$" + variable.getKey()), variable.getValue());
		}

		return patternPairsBuilder.build();
	}

	private static XmlFile replaceConfig(AbstractProject template, AbstractProject implementation, Map<Pattern, String> propertiesMap) throws IOException {
		XmlFile implementationXmlFile = implementation.getConfigFile();
		File implementationFile = implementationXmlFile.getFile();

		assert template.getConfigFile() != null : "template config file shouldn't be null";

		InputStream templateFileStream = new FileInputStream(template.getConfigFile().getFile());
		try {
			OutputStream outputStream = new FileOutputStream(implementationFile);
			try {
				UnixUtils.sed(templateFileStream, outputStream, propertiesMap);
			} finally {
				outputStream.flush();
				outputStream.close();
			}
		} finally {
			templateFileStream.close();
		}
		return implementationXmlFile;
	}

	private static void refreshAndSave(AbstractProject template, final AbstractProject implementation, ImplementationBuildWrapper implementationBuildWrapper, XmlFile implementationXmlFile) throws IOException {
		int attempt = 0;
		AbstractProject newImplementation = null;
//		while(attempt == 0 || (verifySaved(implementation, newImplementation) && attempt < 5)) {
			implementationBuildWrapper.synced = true;
			((Describable)implementation).getDescriptor().load();
			newImplementation = (AbstractProject)implementationXmlFile.unmarshal(implementation);

			DescribableList<BuildWrapper, Descriptor<BuildWrapper>> implementationBuildWrappers = ((BuildableItemWithBuildWrappers) newImplementation).getBuildWrappersList();
			implementationBuildWrappers.add(implementationBuildWrapper);

			List<BuildWrapper> toRemove = new LinkedList<BuildWrapper>();
			for (BuildWrapper buildWrapper : implementationBuildWrappers) {
				if(buildWrapper instanceof TemplateBuildWrapper) {
					if(template.getName().equals(((TemplateBuildWrapper) buildWrapper).getTemplateName())) {
						toRemove.add(buildWrapper);
					}
				}
			}
			for (BuildWrapper buildWrapper : toRemove) {
				implementationBuildWrappers.remove(buildWrapper);
			}

			newImplementation.getConfigFile().write(newImplementation); //do call save() because it calls the event handlers.
			attempt++;
//		}
	}

	private static boolean verifySaved(AbstractProject oldImplementation, AbstractProject newImplementation) {
		if(newImplementation == null) {
			return false;
		}
		return oldImplementation.equals(newImplementation);
	}

	public static Map<String, String> expandToMap(String parameters) {
		Map<String, String> result = new HashMap<String, String>();
		String[] split = parameters.split("\n");
		for (String s : split) {
			if (s.contains("#")) {
				s = s.substring(0, s.indexOf("#")).trim();
			}
			String[] keyValue = s.split("=", 2);
			if (keyValue.length == 2) {
				result.put(keyValue[0], keyValue[1]);
			}
		}
		return result;
	}

	@Exported
	public String getTemplateName() {
		return templateName;
	}

	@Exported
	public String getImplementationName() {
		return implementationName;
	}

	@Exported
	public String getVariables() {
		return variables;
	}

	@Exported
	public boolean isSynced() {
		return synced;
	}

	@Override
	public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
		if(!synced) {
			listener.error("Job not synced!!!!! Open and save the template to sync!");
		}
		return new Environment() {
			@Override
			public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
				if(!synced) {
					listener.error("Job not synced!!!!! Open and save the template to sync!");
				}
				return true;
			}
		};
	}

	@Extension
	public static class DescriptorImpl extends BuildWrapperDescriptor {
		/**
		 * Verifies that the template name both exists and is a TemplateProject
		 * @param value The value to validate
		 * @return FormValidation.ok() if everything checks out.
		 * FormValidation.error(...) if the given value is neither a project or a template.
		 */
		public FormValidation doCheckTemplateName(@QueryParameter String value) {
			if (value == null || value.trim().isEmpty()) {
				return FormValidation.error("Template is a required field.");
			}

			AbstractProject nearest = Project.findNearest(value);
			if(nearest == null || !value.equals(nearest.getName())) {
				return FormValidation.error("Project must exist.");
			}

			if(!(nearest instanceof BuildableItemWithBuildWrappers)) {
				return FormValidation.error("Project must explicitly be defined as a template.");
			}

			TemplateBuildWrapper wrapper = null;
			for (BuildWrapper buildWrapper : ((BuildableItemWithBuildWrappers) nearest).getBuildWrappersList()) {
				if(buildWrapper instanceof TemplateBuildWrapper) {
					wrapper = (TemplateBuildWrapper) buildWrapper;
					break;
				}
			}
			if(wrapper == null) {
				return FormValidation.error("Project must explicitly be defined as a template.");
			}

			return FormValidation.ok();
		}

		@Override
		public boolean isApplicable(AbstractProject<?, ?> item) {
			return item instanceof Describable && item instanceof BuildableItemWithBuildWrappers;
		}

		@Override
		public String getDisplayName() {
			return "Implement Template";
		}
	}
}
