package io.syndesis.server.controller.integration.camelk.customizer;

import io.fabric8.kubernetes.api.model.Secret;
import io.syndesis.common.model.integration.IntegrationDeployment;
import io.syndesis.common.util.SyndesisServerException;
import io.syndesis.server.controller.integration.camelk.crd.ConfigurationSpec;
import io.syndesis.server.controller.integration.camelk.crd.Integration;
import io.syndesis.server.controller.integration.camelk.crd.IntegrationSpec;
import io.syndesis.server.controller.integration.camelk.crd.IntegrationTraitSpec;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static io.syndesis.common.util.Optionals.asStream;

/**
 * Enables specific Knative traits if needed
 */
@Component
public class KnativeCamelKIntegrationCustomizer implements CamelKIntegrationCustomizer {

    private static final String KNATIVE_TRAIT = "knative";
    private static final String KNATIVE_SERVICE_TRAIT = "knative-service";
    private static final String DEPLOYER_TRAIT = "deployer";

    private static final String KNATIVE_SOURCE_CHANNEL_TAG = "knative-source-channel";
    private static final String KNATIVE_SINK_CHANNEL_TAG = "knative-sink-channel";

    private static final String HTTP_PASSIVE_TAG = "http-passive";

    private static final boolean KNATIVE_SERVING_03_COMPAT = true;
    private static final String APPLICATION_PROPERTIES_FILE = "application.properties";

    @Override
    public Integration customize(IntegrationDeployment deployment, Integration integration, Secret secret) {
        integration = customizeChannels(deployment, integration);
        integration = customizeService(deployment, integration);
        integration = customizeProperties(deployment, integration, secret);
        return integration;
    }

    protected Integration customizeChannels(IntegrationDeployment deployment, Integration integration) {
        List<String> sourceChannels = deployment.getSpec().getFlows().stream()
            .flatMap(f -> f.getSteps().stream())
            .flatMap(s -> asStream(s.getAction().flatMap(a -> a.propertyTaggedWith(s.getConfiguredProperties(), KNATIVE_SOURCE_CHANNEL_TAG))))
            .collect(Collectors.toList());

        List<String> sinkChannels = deployment.getSpec().getFlows().stream()
            .flatMap(f -> f.getSteps().stream())
            .flatMap(s -> asStream(s.getAction().flatMap(a -> a.propertyTaggedWith(s.getConfiguredProperties(), KNATIVE_SINK_CHANNEL_TAG))))
            .collect(Collectors.toList());


        if (!sourceChannels.isEmpty() || !sinkChannels.isEmpty()) {
            String sources = sourceChannels.stream().collect(Collectors.joining(","));
            String sinks = sinkChannels.stream().collect(Collectors.joining(","));

            IntegrationSpec.Builder spec = new IntegrationSpec.Builder();
            if (integration.getSpec() != null) {
                spec = spec.from(integration.getSpec());
            }
            integration.setSpec(
                spec.putTraits(KNATIVE_TRAIT, new IntegrationTraitSpec.Builder()
                    .putConfiguration("enabled", "true")
                    .putConfiguration("sources", sources)
                    .putConfiguration("sinks", sinks)
                    .build()
                ).build()
            );
        }

        return integration;
    }

    protected Integration customizeService(IntegrationDeployment deployment, Integration integration) {
        if (isKnativeServiceNeeded(deployment)) {
            IntegrationSpec.Builder spec = new IntegrationSpec.Builder();
            if (integration.getSpec() != null) {
                spec = spec.from(integration.getSpec());
            }
            integration.setSpec(
                spec.putTraits(KNATIVE_SERVICE_TRAIT,
                    new IntegrationTraitSpec.Builder()
                        .putConfiguration("enabled", "true")
                        .putConfiguration("min-scale", "0")
                        .build()
                ).putTraits(DEPLOYER_TRAIT,
                    new IntegrationTraitSpec.Builder()
                        .putConfiguration("kind", KNATIVE_SERVICE_TRAIT)
                        .putConfiguration("container-image", KNATIVE_SERVING_03_COMPAT ? "true" : "false") // slower, but needed for knative serving < 0.4
                        .build()
                ).build()
            );
        }

        return integration;
    }

    protected Integration customizeProperties(IntegrationDeployment deployment, Integration integration, Secret secret) {
        if (KNATIVE_SERVING_03_COMPAT && isKnativeServiceNeeded(deployment)) {
            // Only do this if using Knative serving 0.3
            if (secret.getStringData().containsKey(APPLICATION_PROPERTIES_FILE)) {
                String data = secret.getStringData().get(APPLICATION_PROPERTIES_FILE);
                Properties properties = new Properties();
                try (StringReader reader = new StringReader(data)) {
                    properties.load(reader);
                } catch (IOException e) {
                    throw SyndesisServerException.launderThrowable("Error while reading properties from secret", e);
                }

                IntegrationSpec.Builder spec = new IntegrationSpec.Builder();
                if (integration.getSpec() != null) {
                    spec = spec.from(integration.getSpec());
                }

                for (Map.Entry<Object, Object> kv : properties.entrySet()) {
                    spec = spec.addConfiguration(new ConfigurationSpec.Builder()
                        .type("property")
                        .value(kv.getKey() + "=" + kv.getValue())
                        .build());
                }

                integration.setSpec(spec.build());
            }

        }
        return integration;
    }

    protected boolean isKnativeServiceNeeded(IntegrationDeployment deployment) {
        return deployment.getSpec().getFlows().stream()
            .flatMap(f -> asStream(f.getSteps().stream().findFirst()))
            .flatMap(s -> asStream(s.getAction()))
            .anyMatch(a -> a.getTags().contains(HTTP_PASSIVE_TAG));
    }
}
