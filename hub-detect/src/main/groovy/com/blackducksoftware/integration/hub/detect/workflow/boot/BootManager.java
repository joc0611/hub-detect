package com.blackducksoftware.integration.hub.detect.workflow.boot;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;

import com.blackducksoftware.integration.hub.detect.DetectInfo;
import com.blackducksoftware.integration.hub.detect.DetectInfoUtility;
import com.blackducksoftware.integration.hub.detect.configuration.DetectConfigurationManager;
import com.blackducksoftware.integration.hub.detect.configuration.DetectConfiguration;
import com.blackducksoftware.integration.hub.detect.configuration.DetectConfigurationUtility;
import com.blackducksoftware.integration.hub.detect.configuration.DetectProperty;
import com.blackducksoftware.integration.hub.detect.configuration.DetectPropertySource;
import com.blackducksoftware.integration.hub.detect.exception.DetectUserFriendlyException;
import com.blackducksoftware.integration.hub.detect.exitcode.ExitCodeType;
import com.blackducksoftware.integration.hub.detect.help.DetectArgumentState;
import com.blackducksoftware.integration.hub.detect.help.DetectArgumentStateParser;
import com.blackducksoftware.integration.hub.detect.help.DetectOption;
import com.blackducksoftware.integration.hub.detect.help.DetectOptionManager;
import com.blackducksoftware.integration.hub.detect.help.html.HelpHtmlWriter;
import com.blackducksoftware.integration.hub.detect.help.print.DetectConfigurationPrinter;
import com.blackducksoftware.integration.hub.detect.help.print.DetectInfoPrinter;
import com.blackducksoftware.integration.hub.detect.help.print.HelpPrinter;
import com.blackducksoftware.integration.hub.detect.hub.HubServiceManager;
import com.blackducksoftware.integration.hub.detect.interactive.InteractiveManager;
import com.blackducksoftware.integration.hub.detect.interactive.mode.DefaultInteractiveMode;
import com.blackducksoftware.integration.hub.detect.util.DetectFileManager;
import com.blackducksoftware.integration.hub.detect.util.TildeInPathResolver;
import com.blackducksoftware.integration.hub.detect.workflow.DetectRun;
import com.blackducksoftware.integration.hub.detect.workflow.PhoneHomeManager;
import com.blackducksoftware.integration.hub.detect.workflow.diagnostic.DiagnosticFileManager;
import com.blackducksoftware.integration.hub.detect.workflow.diagnostic.DiagnosticLogManager;
import com.blackducksoftware.integration.hub.detect.workflow.diagnostic.DiagnosticManager;
import com.blackducksoftware.integration.hub.detect.workflow.diagnostic.DiagnosticReportManager;
import com.blackducksoftware.integration.hub.detect.workflow.profiling.BomToolProfiler;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.synopsys.integration.exception.IntegrationException;
import com.synopsys.integration.log.SilentLogger;

import freemarker.template.Configuration;

public class BootManager {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final Gson gson;
    private final JsonParser jsonParser;
    private final Configuration configuration;

    public BootManager(final Gson gson, final JsonParser jsonParser, final Configuration configuration) {
        this.gson = gson;
        this.jsonParser = jsonParser;
        this.configuration = configuration;
    }

    public BootResult boot(final String[] sourceArgs, ConfigurableEnvironment environment) throws DetectUserFriendlyException, IntegrationException {
        DetectRun detectRun = DetectRun.createDefault();
        DetectInfo detectInfo = DetectInfoUtility.createDefaultDetectInfo();

        DetectPropertySource propertySource = new DetectPropertySource(environment);
        DetectConfiguration detectConfiguration = new DetectConfiguration(propertySource);
        DetectOptionManager detectOptionManager = new DetectOptionManager(detectConfiguration, detectInfo);

        final List<DetectOption> options = detectOptionManager.getDetectOptions();

        DetectArgumentState detectArgumentState = parseDetectArgumentState(sourceArgs);

        if (detectArgumentState.isHelp() || detectArgumentState.isDeprecatedHelp() || detectArgumentState.isVerboseHelp()) {
            printAppropriateHelp(options, detectArgumentState);
            return BootResult.exit();
        }

        if (detectArgumentState.isHelpDocument()) {
            printHelpDocument(options, detectInfo);
            return BootResult.exit();
        }

        printDetectInfo(detectInfo);

        if (detectArgumentState.isInteractive()) {
            startInteractiveMode(detectOptionManager, detectConfiguration);
        }

        processDetectConfiguration(detectInfo, detectRun, detectConfiguration, options);

        detectOptionManager.postInit();

        logger.info("Configuration processed completely.");

        DiagnosticManager diagnosticManager = createDiagnostics(detectConfiguration, detectRun, detectArgumentState);

        printConfiguration(detectConfiguration.getBooleanProperty(DetectProperty.DETECT_SUPPRESS_CONFIGURATION_OUTPUT), options);

        checkForInvalidOptions(detectOptionManager);

        HubServiceManager hubServiceManager = new HubServiceManager(detectConfiguration, new DetectConfigurationUtility(detectConfiguration), gson, jsonParser);

        if (detectConfiguration.getBooleanProperty(DetectProperty.DETECT_TEST_CONNECTION)) {
            hubServiceManager.assertHubConnection(new SilentLogger());
            return BootResult.exit();
        }

        if (detectConfiguration.getBooleanProperty(DetectProperty.DETECT_DISABLE_WITHOUT_BLACKDUCK) && !hubServiceManager.testHubConnection(new SilentLogger())) {
            logger.info(String.format("%s is set to 'true' so Detect will not run.", DetectProperty.DETECT_DISABLE_WITHOUT_BLACKDUCK.getPropertyName()));
            return BootResult.exit();
        }

        PhoneHomeManager phoneHomeManager = createPhoneHomeManager(detectInfo, detectConfiguration, hubServiceManager);
        DetectFileManager detectFileManager = new DetectFileManager(detectConfiguration, detectRun, diagnosticManager);

        //Finished, return created objects.
        DetectRunContext detectRunContext = new DetectRunContext();
        detectRunContext.detectConfiguration = detectConfiguration;
        detectRunContext.detectRun = detectRun;
        detectRunContext.detectInfo = detectInfo;
        detectRunContext.detectFileManager = detectFileManager;
        detectRunContext.phoneHomeManager = phoneHomeManager;
        detectRunContext.diagnosticManager = diagnosticManager;
        detectRunContext.hubServiceManager = hubServiceManager;

        BootResult result = new BootResult();
        result.detectRunContext = detectRunContext;
        result.bootType = BootResult.BootType.CONTINUE;
        return result;
    }

    private void printAppropriateHelp(List<DetectOption> detectOptions, DetectArgumentState detectArgumentState) {
        HelpPrinter helpPrinter = new HelpPrinter();
        helpPrinter.printAppropriateHelpMessage(System.out, detectOptions, detectArgumentState);
    }

    private void printHelpDocument(List<DetectOption> detectOptions, DetectInfo detectInfo) {
        HelpHtmlWriter helpHtmlWriter = new HelpHtmlWriter(configuration);
        helpHtmlWriter.writeHtmlDocument(String.format("hub-detect-%s-help.html", detectInfo.getDetectVersion()), detectOptions);
    }

    private void printDetectInfo(DetectInfo detectInfo) {
        DetectInfoPrinter detectInfoPrinter = new DetectInfoPrinter();
        detectInfoPrinter.printInfo(System.out, detectInfo);
    }

    private void printConfiguration(boolean fullConfiguration, List<DetectOption> detectOptions){
        DetectConfigurationPrinter detectConfigurationPrinter = new DetectConfigurationPrinter();
        if (!fullConfiguration) {
            detectConfigurationPrinter.print(System.out, detectOptions);
        }
        detectConfigurationPrinter.printWarnings(System.out, detectOptions);
    }

    private void startInteractiveMode(DetectOptionManager detectOptionManager, DetectConfiguration detectConfiguration) {
        InteractiveManager interactiveManager = new InteractiveManager(detectOptionManager);
        HubServiceManager hubServiceManager = new HubServiceManager(detectConfiguration, new DetectConfigurationUtility(detectConfiguration), gson, jsonParser);
        DefaultInteractiveMode defaultInteractiveMode = new DefaultInteractiveMode(hubServiceManager, detectOptionManager);
        interactiveManager.configureInInteractiveMode(defaultInteractiveMode);
    }

    private DetectArgumentState parseDetectArgumentState(String[] sourceArgs){
        DetectArgumentStateParser detectArgumentStateParser = new DetectArgumentStateParser();
        final DetectArgumentState detectArgumentState = detectArgumentStateParser.parseArgs(sourceArgs);
        return detectArgumentState;
    }

    private void processDetectConfiguration(DetectInfo detectInfo, DetectRun detectRun, DetectConfiguration detectConfiguration, List<DetectOption> detectOptions) throws DetectUserFriendlyException {
        TildeInPathResolver tildeInPathResolver = new TildeInPathResolver(DetectConfigurationManager.USER_HOME, detectInfo.getCurrentOs());
        DetectConfigurationManager detectConfigurationManager = new DetectConfigurationManager(tildeInPathResolver, detectConfiguration);
        detectConfigurationManager.process(detectOptions, detectRun.getRunId());
    }

    private void checkForInvalidOptions(DetectOptionManager detectOptionManager) throws DetectUserFriendlyException{
        final List<DetectOption.OptionValidationResult> invalidDetectOptionResults = detectOptionManager.getAllInvalidOptionResults();
        if (!invalidDetectOptionResults.isEmpty()) {
            throw new DetectUserFriendlyException(invalidDetectOptionResults.get(0).getValidationMessage(), ExitCodeType.FAILURE_GENERAL_ERROR);
        }
    }

    private DiagnosticManager createDiagnostics(DetectConfiguration detectConfiguration, DetectRun detectRun, DetectArgumentState detectArgumentState){
        DiagnosticReportManager diagnosticReportManager = new DiagnosticReportManager(new BomToolProfiler());
        DiagnosticLogManager diagnosticLogManager = new DiagnosticLogManager();
        DiagnosticFileManager diagnosticFileManager = new DiagnosticFileManager();
        DiagnosticManager diagnosticManager = new DiagnosticManager(detectConfiguration, diagnosticReportManager, diagnosticLogManager, detectRun, diagnosticFileManager, detectArgumentState.isDiagnostic(), detectArgumentState.isDiagnosticProtected());
        return diagnosticManager;
    }

    private PhoneHomeManager createPhoneHomeManager(DetectInfo detectInfo, DetectConfiguration detectConfiguration, HubServiceManager hubServiceManager) throws DetectUserFriendlyException, IntegrationException {
        PhoneHomeManager phoneHomeManager = new PhoneHomeManager(detectInfo, detectConfiguration, gson);
        if (detectConfiguration.getBooleanProperty(DetectProperty.BLACKDUCK_OFFLINE_MODE)) {
            phoneHomeManager.initOffline();
        } else {
            hubServiceManager.init();
            phoneHomeManager.init(hubServiceManager.createPhoneHomeService(), hubServiceManager.createPhoneHomeClient(), hubServiceManager.getHubServicesFactory(), hubServiceManager.createHubService(),
                hubServiceManager.createHubRegistrationService(), hubServiceManager.getHubServicesFactory().getRestConnection().getBaseUrl());
        }
        return phoneHomeManager;
    }
}