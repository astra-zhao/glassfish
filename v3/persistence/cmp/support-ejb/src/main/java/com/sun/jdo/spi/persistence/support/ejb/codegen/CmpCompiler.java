/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.jdo.spi.persistence.support.ejb.codegen;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.ResourceBundle;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.sun.jdo.spi.persistence.support.ejb.ejbc.JDOCodeGenerator;
import com.sun.jdo.spi.persistence.utility.I18NHelper;
import com.sun.jdo.spi.persistence.utility.logging.Logger;

import com.sun.logging.LogDomains;
import com.sun.enterprise.deployment.EjbDescriptor;
import com.sun.enterprise.deployment.Application;
import com.sun.enterprise.deployment.EjbBundleDescriptor;
import com.sun.enterprise.deployment.EjbCMPEntityDescriptor;

import com.sun.enterprise.deployment.IASEjbCMPEntityDescriptor;

import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.component.Habitat;

import org.glassfish.ejb.spi.CMPDeployer;
import org.glassfish.api.deployment.DeploymentContext;
import org.glassfish.api.deployment.DeployCommandParameters;
import org.glassfish.deployment.common.DeploymentException;
import org.glassfish.deployment.common.DeploymentUtils;
import com.sun.enterprise.config.serverbeans.JavaConfig;
import org.glassfish.loader.util.ASClassLoaderUtil;

/**
 * Generates concrete impls for CMP beans in an archive. 
 *
 * @author Nazrul Islam
 * @since  JDK 1.4
 */
@Service
public class CmpCompiler implements CMPDeployer {

    @Inject
    private JavaConfig javaConfig;

    @Inject
    private Habitat habitat;

    /**
     * Generates the concrete impls for all CMPs in the application.
     *
     * @throws DeploymentException if this exception was thrown while generating concrete impls
     */
    public void deploy(DeploymentContext ctx) throws DeploymentException {
        
        // deployment descriptor object representation for the archive
        Application application = null;

        // deployment descriptor object representation for each module
        EjbBundleDescriptor bundle = null;

        // ejb name
        String beanName = null; 

        // GeneratorException message if any
        StringBuffer generatorExceptionMsg = null; 

        try {
            // scratchpad variable
            long time; 

            CMPGenerator gen = null;

            try {
                gen = new JDOCodeGenerator();
            } catch (Throwable e) {
                String msg = I18NHelper.getMessage(messages,
                        "cmpc.cmp_generator_class_error",
                        application.getRegistrationName(), 
                        bundle.getModuleDescriptor().getArchiveUri());
                _logger.log(Logger.SEVERE, msg, e);
                generatorExceptionMsg = addGeneratorExceptionMessage(msg, 
                        generatorExceptionMsg);

                throw new DeploymentException(generatorExceptionMsg.toString());
            }

            // stubs dir for the current deployment 
            File stubsDir = ctx.getScratchDir("ejb");

            application = ctx.getModuleMetaData(Application.class);

            if (_logger.isLoggable(Logger.FINE)) {
                _logger.fine( "cmpc.processing_cmp", 
                        application.getRegistrationName());
            }

            List<File> cmpFiles = new ArrayList<File>();
            final ClassLoader jcl = application.getClassLoader();

            bundle = ctx.getModuleMetaData(EjbBundleDescriptor.class);
                
            // If it is a stand alone module then the srcDir is 
            // the ModuleDirectory
            String archiveUri = ctx.getSource().getURI().getSchemeSpecificPart();

            if (_logger.isLoggable(Logger.FINE)) {
                _logger.fine("[CMPC] Module Dir name is "
                        + archiveUri);
            }

            String generatedXmlsPath = ctx.getScratchDir("xml").getCanonicalPath();

            if (_logger.isLoggable(Logger.FINE)) {
                _logger.fine("[CMPC] Generated XML Dir name is "
                        + generatedXmlsPath);
            }

            try {
                long start = System.currentTimeMillis();
                gen.init(bundle, ctx, archiveUri, generatedXmlsPath);
                
                Iterator ejbs=bundle.getEjbs().iterator();
                while ( ejbs.hasNext() ) {

                    EjbDescriptor desc = (EjbDescriptor) ejbs.next();
                    beanName = desc.getName();

                    if (_logger.isLoggable(Logger.FINE)) {
                        _logger.fine("[CMPC] Ejb Class Name: "
                                           + desc.getEjbClassName());
                    }
    
                    if ( desc instanceof IASEjbCMPEntityDescriptor ) {
    
                        // generate concrete CMP class implementation
                        IASEjbCMPEntityDescriptor entd = 
                                (IASEjbCMPEntityDescriptor)desc;
    
                        if (_logger.isLoggable(Logger.FINE)) {
                            _logger.fine(
                                    "[CMPC] Home Object Impl name  is "
                                    + entd.getLocalHomeImplClassName());
                        }
    
                        // generate persistent class
                        ClassLoader ocl = entd.getClassLoader();
                        entd.setClassLoader(jcl);
                    
                        try {
                            gen.generate(entd, stubsDir, stubsDir);
                        } catch (GeneratorException e) {
                            String msg = e.getMessage();
                            _logger.warning(msg);
                            generatorExceptionMsg = addGeneratorExceptionMessage(
                                    msg, generatorExceptionMsg);
                        }  finally {
                            entd.setClassLoader(ocl);
                        }

                    /* WARNING: IASRI 4683195
                     * JDO Code failed when there was a relationship involved
                     * because it depends upon the orginal ejbclasname and hence
                     * this code is shifted to just before the Remote Impl is
                     * generated.Remote/Home Impl generation depends upon this
                     * value
                     */
    
                    } else if (desc instanceof EjbCMPEntityDescriptor ) {
                            //RI code here
                    }

                } // end while ejbs.hasNext()
                beanName = null;

                cmpFiles.addAll(gen.cleanup());

                long end = System.currentTimeMillis();
                _logger.fine("CMP Generation: " + (end - start) + " msec");

            } catch (GeneratorException e) {
                String msg = e.getMessage();
                _logger.warning(msg);
                generatorExceptionMsg = addGeneratorExceptionMessage(msg, 
                        generatorExceptionMsg);
            } 

            bundle = null; // Used in exception processing

            if (generatorExceptionMsg == null) {

                long start = System.currentTimeMillis();
                compileClasses(ctx, cmpFiles, archiveUri, stubsDir);
                long end = System.currentTimeMillis();

                _logger.fine("Java Compilation: " + (end - start) + " msec");
                _logger.fine( "cmpc.done_processing_cmp", 
                        application.getRegistrationName());
             }

        } catch (GeneratorException e) {
            _logger.warning(e.getMessage());
            throw new DeploymentException(e.getMessage());

        } catch (Throwable e) {
            String eType = e.getClass().getName();
            String appName = application.getRegistrationName();
            String exMsg = e.getMessage();

            String msg = null;
            if (bundle == null) {
                // Application or compilation error
                msg = I18NHelper.getMessage(messages,
                    "cmpc.cmp_app_error", eType, appName, exMsg);
            } else {
                String bundleName = bundle.getModuleDescriptor().getArchiveUri();
                if (beanName == null) {
                    // Module processing error
                    msg = I18NHelper.getMessage(messages,
                        "cmpc.cmp_module_error",
                        new Object[] {eType, appName, bundleName, exMsg});
                } else {
                    // CMP bean generation error
                    msg = I18NHelper.getMessage(messages,
                        "cmpc.cmp_bean_error",
                        new Object[] {eType, beanName, appName, bundleName, exMsg});
                }
            }

            _logger.log(Logger.SEVERE, msg, e);

            throw new DeploymentException(msg);
        }

        if (generatorExceptionMsg != null) {
            // We already logged each separate part.
            throw new DeploymentException(generatorExceptionMsg.toString());
        }
    }

    /**
     * Compile .java files.
     *
     * @param    ctx          DeploymentContext associated with the call
     * @param    files        actual source files
     * @param    explodedDir  exploded directory for .class files
     * @param    destDir      destination directory for .class files
     *
     * @exception  GeneratorException  if an error while code compilation
     */
    public void compileClasses(DeploymentContext ctx, List<File> files, 
            String explodedDir, File destDir) throws GeneratorException {

        if (files.size() <= 0) {
            return;
        }

        // class path for javac
        String classPath = ASClassLoaderUtil.getModuleClassPath(habitat, ctx);
        List<String> options    = javaConfig.getJavacOptionsAsList();

        StringBuffer msgBuffer = new StringBuffer();
        try {
            // add the rest of the javac options
            options.add("-d");
            options.add(destDir.toString());
            options.add("-classpath");
            options.add(System.getProperty("java.class.path")
                         + File.pathSeparator + classPath
                         + File.pathSeparator + destDir
                         + File.pathSeparator  + explodedDir);

            if (_logger.isLoggable(Logger.FINE)) {
                for(File file : files) {
                    _logger.fine(I18NHelper.getMessage(messages,
                                    "cmpc.compile", file.getPath()));
                }

                StringBuffer sbuf = new StringBuffer();
                for ( String s : options) {
                    sbuf.append("\n\t").append(s);
                }
                _logger.fine("[CMPC] JAVAC OPTIONS: " + sbuf.toString());
            }

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = 
                   new DiagnosticCollector<JavaFileObject>();
            StandardJavaFileManager manager = 
                    compiler.getStandardFileManager(diagnostics, null, null);
            Iterable compilationUnits = manager.getJavaFileObjectsFromFiles(files);

            long start = System.currentTimeMillis();
            long end = start;

            boolean result = compiler.getTask(
                    null, manager, diagnostics, options, null, compilationUnits).call();

            end = System.currentTimeMillis();
            _logger.fine("JAVA compile time (" + files.size()
                    + " files) = " + (end - start));

            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind().equals(Diagnostic.Kind.NOTE)) {
                    if (_logger.isLoggable(Logger.FINE)) {
                        msgBuffer.append("\n" + diagnostic.getMessage(null));
                    }
                    continue;
                }
                msgBuffer.append("\n" + diagnostic.getMessage(null));
            }

            manager.close();

        } catch(Exception jce) {
            _logger.fine("cmpc.cmp_complilation_exception", jce);
            String msg = I18NHelper.getMessage(messages,
                    "cmpc.cmp_complilation_exception",
                    new Object[] {jce.getMessage()} );
            GeneratorException ge = new GeneratorException(msg);
            ge.initCause(jce);
            throw ge;
        }

        if (msgBuffer.length() > 0) {
            // Log but throw an exception with a shorter message
            _logger.warning(I18NHelper.getMessage(messages, 
                    "cmpc.cmp_complilation_problems", msgBuffer.toString()));
            throw new GeneratorException(I18NHelper.getMessage(
                    messages, "cmpc.cmp_complilation_failed"));
        }

    }

    /** Adds GeneratorException message to the buffer.
     *
     * @param    msg     the message text to add to the buffer.
     * @param    buf    the buffer to use.
     * @return    the new or updated buffer.
     */
    private StringBuffer addGeneratorExceptionMessage(String msg, StringBuffer buf) {
        StringBuffer rc = buf;
        if (rc == null) 
            rc = new StringBuffer(msg);
        else 
            rc.append('\n').append(msg);

        return rc;
    }

    // ---- VARIABLE(S) - PRIVATE --------------------------------------
    private static final Logger _logger  = LogHelperCmpCompiler.getLogger();
    private static final ResourceBundle messages = I18NHelper.loadBundle(CmpCompiler.class);

}
