package confidential.polynomial.creator;

import confidential.interServersCommunication.InterServersCommunication;
import confidential.polynomial.DistributedPolynomial;
import confidential.polynomial.PolynomialCreationContext;
import confidential.polynomial.PolynomialCreationListener;
import confidential.server.ServerConfidentialityScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;

public class PolynomialCreatorFactory {
    private final Logger logger = LoggerFactory.getLogger("polynomial_generation");
    private static final PolynomialCreatorFactory INSTANCE = new PolynomialCreatorFactory();

    public static PolynomialCreatorFactory getInstance() {
        return INSTANCE;
    }

    public PolynomialCreator getNewCreatorFor(PolynomialCreationContext context, int processId, SecureRandom rndGenerator,
                                              ServerConfidentialityScheme confidentialityScheme,
                                              InterServersCommunication serversCommunication,
                                              PolynomialCreationListener creationListener,
                                              DistributedPolynomial distributedPolynomial) {
        switch (context.getReason()) {
            case RECOVERY:
                return new RecoveryPolynomialCreator(
                        context,
                        processId,
                        rndGenerator,
                        confidentialityScheme,
                        serversCommunication,
                        creationListener,
                        distributedPolynomial
                );
            case RESHARING:
                return new ResharingPolynomialCreator(
                        context,
                        processId,
                        rndGenerator,
                        confidentialityScheme,
                        serversCommunication,
                        creationListener,
                        distributedPolynomial
                );
            default:
                logger.error("Unknown polynomial creation reason {}", context.getReason());
                return null;
        }
    }
}
