/*
 * The MIT License
 * 
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi, Tom Huybrechts, Yahoo! Inc.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.tasks;

import hudson.AbortException;
import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.EnvironmentSpecific;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tasks._ant.Messages;
import hudson.tasks._ant.AntConsoleAnnotator;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.DownloadFromUrlInstaller;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolProperty;
import hudson.util.ArgumentListBuilder;
import hudson.util.VariableResolver;
import hudson.util.FormValidation;
import hudson.util.XStream2;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;
import java.util.List;
import java.util.Collections;
import java.util.Set;

/**
 * Ant launcher.
 *
 * @author Kohsuke Kawaguchi
 */
public class Ant extends Builder {
    /**
     * The targets, properties, and other Ant options.
     * Either separated by whitespace or newline.
     */
    private final String targets;

    /**
     * Identifies {@link AntInstallation} to be used.
     */
    private final String antName;

    /**
     * ANT_OPTS if not null.
     */
    private final String antOpts;

    /**
     * Optional build script path relative to the workspace.
     * Used for the Ant '-f' option.
     */
    private final String buildFile;

    /**
     * Optional properties to be passed to Ant. Follows {@link Properties} syntax.
     */
    private final String properties;
    
    @DataBoundConstructor
    public Ant(String targets,String antName, String antOpts, String buildFile, String properties) {
        this.targets = targets;
        this.antName = antName;
        this.antOpts = Util.fixEmptyAndTrim(antOpts);
        this.buildFile = Util.fixEmptyAndTrim(buildFile);
        this.properties = Util.fixEmptyAndTrim(properties);
    }

	public String getBuildFile() {
		return buildFile;
	}

	public String getProperties() {
		return properties;
	}

    public String getTargets() {
        return targets;
    }

    /**
     * Gets the Ant to invoke,
     * or null to invoke the default one.
     */
    public AntInstallation getAnt() {
        for( AntInstallation i : getDescriptor().getInstallations() ) {
            if(antName!=null && antName.equals(i.getName()))
                return i;
        }
        return null;
    }

    /**
     * Gets the ANT_OPTS parameter, or null.
     */
    public String getAntOpts() {
        return antOpts;
    }

    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        ArgumentListBuilder args = new ArgumentListBuilder();

        EnvVars env = build.getEnvironment(listener);
        env.overrideAll(build.getBuildVariables());
        
        AntInstallation ai = getAnt();
        if(ai==null) {
            args.add(launcher.isUnix() ? "ant" : "ant.bat");
        } else {
            Node node = Computer.currentComputer().getNode();
            if (node == null) {
                throw new AbortException(Messages.Ant_NodeOffline());
            }
            ai = ai.forNode(node, listener);
            ai = ai.forEnvironment(env);
            String exe = ai.getExecutable(launcher);
            if (exe==null) {
                throw new AbortException(Messages.Ant_ExecutableNotFound(ai.getName()));
            }
            args.add(exe);
        }

        VariableResolver<String> vr = new VariableResolver.ByMap<String>(env);
        String buildFile = env.expand(this.buildFile);
        String targets = env.expand(this.targets);

        FilePath buildFilePath = chooseBuildFilePath(build, buildFile, targets);


        if (!buildFilePath.exists()) {
            throw new AbortException("Unable to find build script at "+ buildFilePath);
        }

        if(buildFile!=null) {
            args.add("-file", buildFilePath.getName());
        }

        Set<String> sensitiveVars = build.getSensitiveBuildVariables();

        args.addKeyValuePairs("-D",build.getBuildVariables(),sensitiveVars);

        args.addKeyValuePairsFromPropertyString("-D",properties,vr,sensitiveVars);

        args.addTokenized(targets.replaceAll("[\t\r\n]+"," "));

        if(ai!=null)
            ai.buildEnvVars(env);
        if(antOpts!=null)
            env.put("ANT_OPTS",env.expand(antOpts));

        if(!launcher.isUnix()) {
            args = toWindowsCommand(args.toWindowsCommand());
        }

        long startTime = System.currentTimeMillis();
        try {
            AntConsoleAnnotator aca = new AntConsoleAnnotator(listener.getLogger(),build.getCharset());
            int r;
            try {
                r = launcher.launch().cmds(args).envs(env).stdout(aca).pwd(buildFilePath.getParent()).join();
            } finally {
                aca.forceEol();
            }
            return r==0;
        } catch (IOException e) {
            Util.displayIOException(e,listener);

            String errorMessage = Messages.Ant_ExecFailed();
            if(ai==null && (System.currentTimeMillis()-startTime)<1000) {
                if(getDescriptor().getInstallations()==null)
                    // looks like the user didn't configure any Ant installation
                    errorMessage += Messages.Ant_GlobalConfigNeeded();
                else
                    // There are Ant installations configured but the project didn't pick it
                    errorMessage += Messages.Ant_ProjectConfigNeeded();
            }
            throw new AbortException(errorMessage);
        }
    }

    /**
     * Backward compatibility by checking the number of parameters
     *
     */
    protected static ArgumentListBuilder toWindowsCommand(ArgumentListBuilder args) {
        List<String> arguments = args.toList();

        if (arguments.size() > 3) { // "cmd.exe", "/C", "ant.bat", ...
            // branch for core equals or greater than 1.654
            boolean[] masks = args.toMaskArray();
            // don't know why are missing single quotes.

            args = new ArgumentListBuilder();
            args.add(arguments.get(0), arguments.get(1)); // "cmd.exe", "/C", ...

            int size = arguments.size();
            for (int i = 2; i < size; i++) {
                String arg = arguments.get(i).replaceAll("^(-D[^\" ]+)=$", "$0\"\"");

                if (masks[i]) {
                    args.addMasked(arg);
                } else {
                    args.add(arg);
                }
            }
        } else {
            // branch for core under 1.653 (backward compatibility)
            // For some reason, ant on windows rejects empty parameters but unix does not.
            // Add quotes for any empty parameter values:
            List<String> newArgs = new ArrayList<String>(args.toList());
            newArgs.set(newArgs.size() - 1, newArgs.get(newArgs.size() - 1).replaceAll(
                    "(?<= )(-D[^\" ]+)= ", "$1=\"\" "));
            args = new ArgumentListBuilder(newArgs.toArray(new String[newArgs.size()]));
        }

        return args;
    }

    private static FilePath buildFilePath(FilePath base, String buildFile, String targets) {
        if(buildFile!=null)     return base.child(buildFile);
        // some users specify the -f option in the targets field, so take that into account as well.
        // see 
        String[] tokens = Util.tokenize(targets);
        for (int i = 0; i<tokens.length-1; i++) {
            String a = tokens[i];
            if(a.equals("-f") || a.equals("-file") || a.equals("-buildfile"))
                return base.child(tokens[i+1]);
        }
        return base.child("build.xml");
    }

    private static FilePath chooseBuildFilePath(AbstractBuild<?,?> build, String buildFile, String targets) throws IOException, InterruptedException {

        FilePath buildFilePath = buildFilePath(build.getModuleRoot(), buildFile, targets);

        // because of the poor choice of getModuleRoot() with CVS/Subversion, people often get confused
        // with where the build file path is relative to. Now it's too late to change this behavior
        // due to compatibility issue, but at least we can make this less painful by looking for errors
        // and diagnosing it nicely. See HUDSON-1782

        // first check if this appears to be a valid relative path from workspace root
        FilePath workspaceFilePath = build.getWorkspace();
        if (workspaceFilePath == null) {
            throw new AbortException("Workspace is not available. Agent may be disconnected.");
        }

        if(!buildFilePath.exists()) {
            buildFilePath = buildFilePath(workspaceFilePath, buildFile, targets);
        }

        // second check if this appears to be a valid absolute path
        if(!buildFilePath.exists()) {
            File file = new File(buildFile);
            buildFilePath = new FilePath(file);
        }

        return buildFilePath;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @CopyOnWrite
        private volatile AntInstallation[] installations = new AntInstallation[0];

        public DescriptorImpl() {
            load();
        }

        protected DescriptorImpl(Class<? extends Ant> clazz) {
            super(clazz);
        }

        /**
         * Obtains the {@link AntInstallation.DescriptorImpl} instance.
         */
        public AntInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(AntInstallation.DescriptorImpl.class);
        }

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public String getDisplayName() {
            return Messages.Ant_DisplayName();
        }

        public AntInstallation[] getInstallations() {
            return Arrays.copyOf(installations, installations.length);
        }

        public void setInstallations(AntInstallation... antInstallations) {
            this.installations = antInstallations;
            save();
        }
    }

    /**
     * Represents the Ant installation on the system.
     */
    public static final class AntInstallation extends ToolInstallation implements
            EnvironmentSpecific<AntInstallation>, NodeSpecific<AntInstallation> {
        // to remain backward compatible with earlier Hudson that stored this field here.
        @Deprecated
        private transient String antHome;

        @DataBoundConstructor
        public AntInstallation(String name, String home, List<? extends ToolProperty<?>> properties) {
            super(name, launderHome(home), properties);
        }

        /**
         * @deprecated as of 1.308
         *      Use {@link #AntInstallation(String, String, List)}
         */
        public AntInstallation(String name, String home) {
            this(name,home,Collections.<ToolProperty<?>>emptyList());
        }

        private static String launderHome(String home) {
            if(home.endsWith("/") || home.endsWith("\\")) {
                // see https://issues.apache.org/bugzilla/show_bug.cgi?id=26947
                // Ant doesn't like the trailing slash, especially on Windows
                return home.substring(0,home.length()-1);
            } else {
                return home;
            }
        }

        /**
         * install directory.
         *
         * @deprecated as of 1.307. Use {@link #getHome()}.
         */
        public String getAntHome() {
            return getHome();
        }

        public void buildEnvVars(EnvVars env) {
            env.put("ANT_HOME",getHome());
        }

        /**
         * Gets the executable path of this Ant on the given target system.
         */
        public String getExecutable(Launcher launcher) throws IOException, InterruptedException {
            return launcher.getChannel().call(new MasterToSlaveCallable<String,IOException>() {
                private static final long serialVersionUID = 906341330603832653L;
                public String call() throws IOException {
                    File exe = getExeFile();
                    if(exe.exists())
                        return exe.getPath();
                    return null;
                }
            });
        }

        private File getExeFile() {
            String execName = Functions.isWindows() ? "ant.bat" : "ant";
            String home = Util.replaceMacro(getHome(), EnvVars.masterEnvVars);

            return new File(home,"bin/"+execName);
        }

        /**
         * Returns true if the executable exists.
         */
        public boolean getExists() throws IOException, InterruptedException {
            return getExecutable(new Launcher.LocalLauncher(TaskListener.NULL))!=null;
        }

        private static final long serialVersionUID = 1L;

        public AntInstallation forEnvironment(EnvVars environment) {
            return new AntInstallation(getName(), environment.expand(getHome()), getProperties().toList());
        }

        public AntInstallation forNode(Node node, TaskListener log) throws IOException, InterruptedException {
            return new AntInstallation(getName(), translateFor(node, log), getProperties().toList());
        }

        @Extension
        @SuppressFBWarnings(value="NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification="https://github.com/jenkinsci/jenkins/pull/2094")
        public static class DescriptorImpl extends ToolDescriptor<AntInstallation> {

            @Override
            public String getDisplayName() {
                return "Ant";
            }

            // for compatibility reasons, the persistence is done by Ant.DescriptorImpl  
            @Override
            public AntInstallation[] getInstallations() {
                return Jenkins.getInstance().getDescriptorByType(Ant.DescriptorImpl.class).getInstallations();
            }

            @Override
            public void setInstallations(AntInstallation... installations) {
                Jenkins.getInstance().getDescriptorByType(Ant.DescriptorImpl.class).setInstallations(installations);
            }

            @Override
            public List<? extends ToolInstaller> getDefaultInstallers() {
                return Collections.singletonList(new AntInstaller(null));
            }

            /**
             * Checks if the ANT_HOME is valid.
             */
            public FormValidation doCheckHome(@QueryParameter File value) {
                // this can be used to check the existence of a file on the server, so needs to be protected
                if(!Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER))
                    return FormValidation.ok();

                if(value.getPath().equals(""))
                    return FormValidation.ok();

                if(!value.isDirectory())
                    return FormValidation.error(Messages.Ant_NotADirectory(value));

                File antJar = new File(value,"lib/ant.jar");
                if(!antJar.exists())
                    return FormValidation.error(Messages.Ant_NotAntDirectory(value));

                return FormValidation.ok();
            }

            public FormValidation doCheckName(@QueryParameter String value) {
                return FormValidation.validateRequired(value);
            }
        }

        public static class ConverterImpl extends ToolConverter {
            public ConverterImpl(XStream2 xstream) { super(xstream); }
            @Override protected String oldHomeField(ToolInstallation obj) {
                return ((AntInstallation)obj).antHome;
            }
        }
    }

    /**
     * Automatic Ant installer from apache.org.
     */
    public static class AntInstaller extends DownloadFromUrlInstaller {
        @DataBoundConstructor
        public AntInstaller(String id) {
            super(id);
        }

        @Extension
        public static final class DescriptorImpl extends DownloadFromUrlInstaller.DescriptorImpl<AntInstaller> {
            public String getDisplayName() {
                return Messages.Ant_InstallFromApache();
            }

            @Override
            public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
                return toolType==AntInstallation.class;
            }
        }
    }
}
