/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.integration.ejb.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.PropertyPermission;
import java.util.concurrent.Callable;

import jakarta.ejb.EJBAccessException;
import javax.naming.InitialContext;

import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.test.categories.CommonCriteria;
import org.jboss.as.test.integration.ejb.security.runasprincipal.Caller;
import org.jboss.as.test.integration.ejb.security.runasprincipal.CallerRunAsPrincipal;
import org.jboss.as.test.integration.ejb.security.runasprincipal.CallerWithIdentity;
import org.jboss.as.test.integration.ejb.security.runasprincipal.SingletonBean;
import org.jboss.as.test.integration.ejb.security.runasprincipal.StatelessBBean;
import org.jboss.as.test.integration.ejb.security.runasprincipal.WhoAmI;
import org.jboss.as.test.integration.ejb.security.runasprincipal.transitive.SimpleSingletonBean;
import org.jboss.as.test.integration.ejb.security.runasprincipal.transitive.SingletonStartupBean;
import org.jboss.as.test.integration.ejb.security.runasprincipal.transitive.StatelessSingletonUseBean;
import org.jboss.as.test.integration.security.common.AbstractSecurityDomainSetup;
import org.jboss.as.test.shared.TestLogHandlerSetupTask;
import org.jboss.as.test.shared.integration.ejb.security.Util;
import org.jboss.as.test.shared.util.LoggingUtil;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.wildfly.security.auth.permission.ChangeRoleMapperPermission;
import org.wildfly.security.permission.ElytronPermission;

import static org.jboss.as.test.shared.PermissionUtils.createFilePermission;
import static org.jboss.as.test.shared.PermissionUtils.createPermissionsXmlAsset;

/**
 * Migration of test from EJB3 testsuite [JBQA-5451] Testing calling with runasprincipal annotation (ejbthree1945)
 *
 * @author Carlo de Wolf, Ondrej Chaloupka
 */
@RunWith(Arquillian.class)
@ServerSetup({EjbSecurityDomainSetup.class, RunAsPrincipalTestCase.TestLogHandlerSetup.class})
@Category(CommonCriteria.class)
public class RunAsPrincipalTestCase  {

    private static final Logger log = Logger.getLogger(RunAsPrincipalTestCase.class);
    private static final String STARTUP_SINGLETON_DEPLOYMENT = "startup-transitive-singleton";
    private static final String DEPLOYMENT = "runasprincipal-test";

    @ArquillianResource
    public Deployer deployer;

    @Deployment(name = STARTUP_SINGLETON_DEPLOYMENT, managed = false, testable = false)
    public static Archive<?> runAsStartupTransitiveDeployment() {
        // using JavaArchive doesn't work, because of a bug in Arquillian, it only deploys wars properly
        final WebArchive war = ShrinkWrap.create(WebArchive.class, STARTUP_SINGLETON_DEPLOYMENT + ".war")
                .addClass(WhoAmI.class)
                .addClass(StatelessBBean.class)
                .addClass(SingletonStartupBean.class)
                .addPackage(Assert.class.getPackage())
                .addClass(Util.class)
                .addClass(Entry.class)
                .addClass(RunAsPrincipalTestCase.class)
                .addClasses(AbstractSecurityDomainSetup.class, EjbSecurityDomainSetup.class)
                .addAsWebInfResource(RunAsPrincipalTestCase.class.getPackage(), "jboss-ejb3.xml", "jboss-ejb3.xml")
                .addAsManifestResource(new StringAsset("Dependencies: org.jboss.as.controller-client,org.jboss.dmr\n"), "MANIFEST.MF")
                .addAsManifestResource(createPermissionsXmlAsset(new ElytronPermission("getSecurityDomain"),
                                                                 new ElytronPermission("authenticate")), "permissions.xml");
        war.addPackage(CommonCriteria.class.getPackage());
        return war;
    }

    @Deployment
    public static Archive<?> runAsDeployment() {
        // using JavaArchive doesn't work, because of a bug in Arquillian, it only deploys wars properly
        final WebArchive war = ShrinkWrap.create(WebArchive.class, DEPLOYMENT + ".war")
                .addPackage(WhoAmI.class.getPackage())
                .addClass(SimpleSingletonBean.class)
                .addClass(StatelessSingletonUseBean.class)
                .addClass(Util.class)
                .addClass(Entry.class)
                .addClass(RunAsPrincipalTestCase.class)
                .addClass(TestLogHandlerSetupTask.class)
                .addClass(LoggingUtil.class)
                .addClasses(AbstractSecurityDomainSetup.class, EjbSecurityDomainSetup.class)
                .addAsWebInfResource(RunAsPrincipalTestCase.class.getPackage(), "jboss-ejb3.xml", "jboss-ejb3.xml")
                .addAsManifestResource(new StringAsset("Dependencies: org.jboss.as.controller-client,org.jboss.dmr\n"), "MANIFEST.MF")
               // TODO WFLY-15289 The Elytron permissions need to be checked, should a deployment really need these?
                .addAsManifestResource(createPermissionsXmlAsset(new ElytronPermission("getSecurityDomain"),
                        new PropertyPermission("jboss.server.log.dir", "read"),
                        createFilePermission("read", "standalone", "log", TEST_LOG_FILE_NAME),
                        new ElytronPermission("authenticate"),
                        new ElytronPermission("getIdentity"),
                        new ElytronPermission("createAdHocIdentity"),
                        new ChangeRoleMapperPermission("ejb"),
                        new ElytronPermission("setRunAsPrincipal")), "permissions.xml");
        war.addPackage(CommonCriteria.class.getPackage());
        return war;
    }

    private WhoAmI lookupCallerWithIdentity() throws Exception {
        return (WhoAmI)new InitialContext().lookup("java:module/" + CallerWithIdentity.class.getSimpleName() + "!" + WhoAmI.class.getName());
    }

    private WhoAmI lookupSingleCallerWithIdentity() throws Exception {
        return (WhoAmI)new InitialContext().lookup("java:module/" + SingletonBean.class.getSimpleName() + "!" + WhoAmI.class.getName());
    }

    private WhoAmI lookupCaller() throws Exception {
        return (WhoAmI)new InitialContext().lookup("java:module/" + Caller.class.getSimpleName() + "!" + WhoAmI.class.getName());
    }

    private WhoAmI lookupCallerRunAsPrincipal() throws Exception {
        return (WhoAmI)new InitialContext().lookup("java:module/" + CallerRunAsPrincipal.class.getSimpleName() + "!" + WhoAmI.class.getName());
    }


    private WhoAmI lookupSingletonUseBeanWithIdentity() throws Exception {
        return (WhoAmI) new InitialContext().lookup("java:module/" + StatelessSingletonUseBean.class.getSimpleName() + "!" + WhoAmI.class.getName());
    }

    @Test
    public void testJackInABox() throws Exception {
        final Callable<Void> callable = () -> {
            WhoAmI bean =  lookupCallerWithIdentity();
            String actual = bean.getCallerPrincipal();
            Assert.assertEquals("jackinabox", actual);
            return null;
        };
        Util.switchIdentitySCF("user1", "password1", callable);
    }

    @Test
    public void testSingletonPostconstructSecurity() throws Exception {
        final Callable<Void> callable = () -> {
            WhoAmI bean = lookupSingleCallerWithIdentity();
            String actual = bean.getCallerPrincipal();
            Assert.assertEquals("Helloween", actual);
            return null;
        };
        Util.switchIdentitySCF("user1", "password1", callable);
    }

    /**
     * Test when only RunAsPrincipal is defined, it should fail but also warn the user about
     * configuration problem.
     * @throws Exception
     */
    @Test
    public void testRunAsPrincipal() throws Exception {
        WhoAmI bean = lookupCallerRunAsPrincipal();
        try {
            String actual = bean.getCallerPrincipal();
            Assert.fail("Expected EJBAccessException and it was get identity: " + actual);
        } catch (EJBAccessException e) {
            // good
            Assert.assertTrue("@RunAs warning not found", LoggingUtil.hasLogMessage(TEST_LOG_FILE_NAME, LOG_MESSAGE));
        }
    }

    @Test
    public void testAnonymous() throws Exception {
        final Callable<Void> callable = () -> {
            WhoAmI bean = lookupCaller();
            String actual = bean.getCallerPrincipal();
            Assert.assertEquals("anonymous", actual);
            return null;
        };
        Util.switchIdentitySCF("user1", "password1", callable);
    }

    @Test
    @OperateOnDeployment(STARTUP_SINGLETON_DEPLOYMENT)
    public void testStartupSingletonPostconstructSecurityNotPropagating() {
        try {
            deployer.deploy(STARTUP_SINGLETON_DEPLOYMENT);
            Assert.fail("Deployment should fail");
        } catch (Exception dex) {
            Throwable t = checkEjbException(dex);
            log.trace("Expected deployment error because the Singleton has nosecurity context per itself", dex.getCause());
            MatcherAssert.assertThat(t.getMessage(), t.getMessage(), CoreMatchers.containsString("WFLYEJB0364"));
        } finally {
            deployer.undeploy(STARTUP_SINGLETON_DEPLOYMENT);
        }
    }

    @Test
    public void testSingletonPostconstructSecurityNotPropagating() throws Exception {
        final Callable<Void> callable = () -> {
            WhoAmI bean = lookupSingletonUseBeanWithIdentity(); //To load the singleton
            bean.getCallerPrincipal();
            Assert.fail("EJB call should fail - identity should not be propagated from @PostConstruct method");
            return null;
        };
        try {
            Util.switchIdentitySCF("user1", "password1", callable);
        } catch (Exception dex) {
            Throwable t = checkEjbException(dex);
            log.trace("Expected EJB call fail because identity should not be propagated from @PostConstruct method", dex.getCause());
            MatcherAssert.assertThat(t.getMessage(), t.getMessage(), CoreMatchers.containsString("WFLYEJB0364"));
        }
    }

    private Throwable checkEjbException(Throwable ex) {
        if (ex instanceof EJBAccessException) {
            return ex;
        }
        if (ex.getCause() != null) {
            return checkEjbException(ex.getCause());
        }
        return ex;
    }



    private static final String TEST_HANDLER_NAME;
    private static final String TEST_LOG_FILE_NAME;
    private static final String LOG_MESSAGE;

    static {
        /*
         * Make both the test handler name and the test log file specific for this class and execution so that we do not
         * interfere with other test classes or multiple subsequent executions of this class against the same container
         */
        TEST_HANDLER_NAME = "test-" + RunAsPrincipalTestCase.class.getSimpleName();
        TEST_LOG_FILE_NAME = TEST_HANDLER_NAME + ".log";
        LOG_MESSAGE = "WFLYEJB0510";
    }

    public static class TestLogHandlerSetup extends TestLogHandlerSetupTask {

        @Override
        public Collection<String> getCategories() {
            return Arrays.asList("org.jboss.as.ejb3.deployment");
        }

        @Override
        public String getLevel() {
            return "WARN";
        }
        @Override
        public String getHandlerName() {
            return TEST_HANDLER_NAME;
        }
        @Override
        public String getLogFileName() {
            return TEST_LOG_FILE_NAME;
        }
    }
}
