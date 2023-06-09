package confidential.server;

import bftsmart.reconfiguration.BatchReconfigurationRequest;
import bftsmart.reconfiguration.IReconfigurationListener;
import bftsmart.statemanagement.ApplicationState;
import bftsmart.statemanagement.StateManager;
import bftsmart.tom.MessageContext;
import bftsmart.tom.ReplicaContext;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.server.ProposeRequestVerifier;
import bftsmart.tom.server.Recoverable;
import bftsmart.tom.server.SingleExecutable;
import bftsmart.tom.server.defaultservices.CommandsInfo;
import bftsmart.tom.server.defaultservices.DefaultApplicationState;
import bftsmart.tom.util.TOMUtil;
import confidential.ConfidentialMessage;
import confidential.Configuration;
import confidential.MessageType;
import confidential.Metadata;
import confidential.encrypted.EncryptedConfidentialData;
import confidential.encrypted.EncryptedConfidentialMessage;
import confidential.encrypted.EncryptedPublishedShares;
import confidential.encrypted.EncryptedVerifiableShare;
import confidential.facade.server.ConfidentialSingleExecutable;
import confidential.interServersCommunication.InterServersCommunication;
import confidential.polynomial.DistributedPolynomial;
import confidential.polynomial.ProposalSetMessage;
import confidential.statemanagement.ConfidentialSnapshot;
import confidential.statemanagement.ConfidentialStateLog;
import confidential.statemanagement.ConfidentialStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vss.commitment.Commitment;
import vss.commitment.CommitmentScheme;
import vss.commitment.CommitmentUtils;
import vss.commitment.constant.ConstantCommitment;
import vss.facade.SecretSharingException;
import vss.secretsharing.VerifiableShare;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class ConfidentialRecoverable implements SingleExecutable, Recoverable,
        ProposeRequestVerifier, IReconfigurationListener {
    private final Logger logger = LoggerFactory.getLogger("confidential");
    private ServerConfidentialityScheme confidentialityScheme;
    private CommitmentScheme commitmentScheme;
    private final int processId;
    private ReplicaContext replicaContext;
    private ConfidentialStateLog log;
    private ReentrantLock stateLock;
    private final ReentrantLock logLock;
    private ConfidentialStateManager stateManager;
    private InterServersCommunication interServersCommunication;
    private int checkpointPeriod;
    private final List<byte[]> commands;
    private final List<MessageContext> msgContexts;
    private final boolean useTLSEncryption;
    private final ConfidentialSingleExecutable confidentialExecutor;
    private DistributedPolynomial distributedPolynomial;
    private boolean isLinearCommitmentScheme;
    private final boolean isCombinePrivateAndCommonData;
    // Not the best solution. Requests failed during consensus, will not be removed from this map
    private final Map<Integer, Request> deserializedRequests;
    private final boolean verifyClientsRequests;

    public ConfidentialRecoverable(int processId, ConfidentialSingleExecutable confidentialExecutor) {
        this.processId = processId;
        this.confidentialExecutor = confidentialExecutor;
        this.logLock = new ReentrantLock();
        this.commands = new ArrayList<>();
        this.msgContexts = new ArrayList<>();
        this.useTLSEncryption = Configuration.getInstance().useTLSEncryption();
        this.deserializedRequests = new ConcurrentHashMap<>();
        this.verifyClientsRequests = Configuration.getInstance().isVerifyClientRequests();
        this.isCombinePrivateAndCommonData = Configuration.getInstance().isSendAllSharesTogether();
    }

    @Override
    public void setReplicaContext(ReplicaContext replicaContext) {
        logger.debug("setting replica context");
        this.replicaContext = replicaContext;
        this.stateLock = new ReentrantLock();
        interServersCommunication = new InterServersCommunication(
                replicaContext.getServerCommunicationSystem(), replicaContext.getSVController());
        checkpointPeriod = replicaContext.getStaticConfiguration().getCheckpointPeriod();
        try {
            this.confidentialityScheme = new ServerConfidentialityScheme(processId, replicaContext.getCurrentView());
            this.commitmentScheme = confidentialityScheme.getCommitmentScheme();
            this.isLinearCommitmentScheme = confidentialityScheme.isLinearCommitmentScheme();
            this.distributedPolynomial = new DistributedPolynomial(replicaContext.getSVController(), interServersCommunication,
                    confidentialityScheme);
            new Thread(distributedPolynomial, "Distributed polynomial Manager").start();
            stateManager.setDistributedPolynomial(distributedPolynomial);
            stateManager.setConfidentialityScheme(confidentialityScheme);
            log = getLog();
            stateManager.askCurrentConsensusId();
        } catch (SecretSharingException e) {
            logger.error("Failed to initialize ServerConfidentialityScheme", e);
        }
    }

    @Override
    public boolean isValidRequest(TOMMessage request) {
        logger.debug("Checking request: {} - {}", request.getReqType(),
                request.getSequence());
        if (request.getMetadata() == null)
            return true;
        Metadata metadata = Metadata.getMessageType(request.getMetadata()[0]);
        logger.debug("Metadata: {}", metadata);
        if (metadata == Metadata.POLYNOMIAL_PROPOSAL_SET) {
            Request req = preprocessRequest(request.getContent(), request.getPrivateContent(), request.getSender());
            if (req == null || req.getType() != MessageType.APPLICATION) {
                logger.error("Unknown request type to verify");
                return false;
            }
            byte[] m =
                    Arrays.copyOfRange(req.getPlainData(), 1,
                            req.getPlainData().length);
            try (ByteArrayInputStream bis = new ByteArrayInputStream(m);
                 ObjectInput in = new ObjectInputStream(bis)) {
                ProposalSetMessage proposalSetMessage = new ProposalSetMessage();
                proposalSetMessage.readExternal(in);
                return distributedPolynomial.isValidProposalSet(proposalSetMessage);
            } catch (IOException | ClassNotFoundException e) {
                logger.error("Failed to deserialize polynomial message of type " +
                        "{}", Metadata.POLYNOMIAL_PROPOSAL_SET, e);
                return false;
            }
        } else if (metadata == Metadata.VERIFY) {
            if (!verifyClientsRequests)
                return true;
            Request req = preprocessRequest(request.getContent(), request.getPrivateContent(), request.getSender());
            if (req == null || req.getShares() == null) {
                return false;
            }
            deserializedRequests.put(hashRequest(request.getSender(), request.getSession(), request.getSequence()), req);
            for (VerifiableShare vs : req.getShares()) {
                boolean isValid = commitmentScheme.checkValidityWithoutPreComputation(vs.getShare(), vs.getCommitments());
                if (!isValid) {
                    logger.warn("Client {} sent me an invalid share", request.getSender());
                    return false;
                }
            }
            return true;
        } else if (metadata == Metadata.DOES_NOT_VERIFY) {
            return true;
        } else {
            logger.error("Unknown metadata {}", metadata);
            return false;
        }
    }

    private int hashRequest(int sender, int session, int sequence) {
        int hash = sender;
        hash = 31 * hash + session;
        hash = 31 * hash + sequence;
        return hash;
    }

    private ConfidentialStateLog getLog() {
        if (log == null)
            log = initLog();
        return log;
    }

    private ConfidentialStateLog initLog() {
        if (!replicaContext.getStaticConfiguration().isToLog())
            return null;
        ConfidentialSnapshot snapshot = confidentialExecutor.getConfidentialSnapshot();
        byte[] state = snapshot.serialize();
        if (replicaContext.getStaticConfiguration().logToDisk()) {
            logger.error("Log to disk not implemented");
            return null;
        }
        logger.info("Logging to memory");
        return new ConfidentialStateLog(processId, checkpointPeriod, state, TOMUtil.computeHash(state));
    }

    @Override
    public ApplicationState getState(int cid, boolean sendState) {
        logLock.lock();
        logger.debug("Getting state until CID {}", cid);
        ApplicationState state = (cid > -1 ? getLog().getApplicationState(cid, sendState)
                : new DefaultApplicationState());
        if (state == null ||
                (replicaContext.getStaticConfiguration().isBFT()
                        && state.getCertifiedDecision(replicaContext.getSVController()) == null))
            state = new DefaultApplicationState();
        logLock.unlock();
        return state;
    }

    @Override
    public int setState(ApplicationState recvState) {
        int lastCID = -1;
        if (recvState instanceof DefaultApplicationState) {
            DefaultApplicationState state = (DefaultApplicationState)recvState;
            logger.info("I'm going to update myself from CID {} to CID {}",
                    state.getLastCheckpointCID(), state.getLastCID());

            stateLock.lock();
            logLock.lock();
            log.update(state);

            int lastCheckpointCID = log.getLastCheckpointCID();
            lastCID = log.getLastCID();


            if (state.getSerializedState() != null) {
                logger.info("Installing snapshot up to CID {}", lastCheckpointCID);
                ConfidentialSnapshot snapshot = ConfidentialSnapshot.deserialize(state.getSerializedState());
                confidentialExecutor.installConfidentialSnapshot(snapshot);
            }

            for (int cid = lastCheckpointCID + 1; cid <= lastCID; cid++) {
                try {
                    logger.debug("Processing and verifying batched requests for CID {}", cid);
                    CommandsInfo cmdInfo = log.getMessageBatch(cid);
                    if (cmdInfo == null) {
                        logger.warn("Consensus {} is null", cid);
                        continue;
                    }
                    byte[][] commands = cmdInfo.commands;
                    MessageContext[] msgCtx = cmdInfo.msgCtx;

                    if (commands == null || msgCtx == null || msgCtx[0].isNoOp())
                        continue;

                    for (int i = 0; i < commands.length; i++) {
                        Request request = Request.deserialize(commands[i]);
                        if (request == null) {
                            logger.warn("Request is null");
                            continue;
                        }
                        if (request.getType() == MessageType.APPLICATION) {
                            logger.debug("Ignoring application request");
                            continue;
                        }
                        if (request.getType() == MessageType.RECONFIGURATION) {
                            logger.debug("Ignoring reconfiguration request");
                            continue;
                        }
                        confidentialExecutor.appExecuteOrdered(request.getPlainData(), request.getShares(), msgCtx[i]);
                    }
                } catch (Exception e) {
                    logger.error("Failed to process and verify batched requests for CID {}", cid, e);
                    if (e instanceof ArrayIndexOutOfBoundsException) {
                        logger.info("Last checkpoint CID: {}", lastCheckpointCID);
                        logger.info("Last CID: {}", lastCID);
                        logger.info("Number of messages expected to be in the batch: {}", (log.getLastCID() - log.getLastCheckpointCID() + 1));
                        logger.info("Number of messages in the batch: {}", log.getMessageBatches().length);
                    }
                }
            }
            logLock.unlock();
            stateLock.unlock();
        }
        return lastCID;
    }

    @Override
    public StateManager getStateManager() {
        if (stateManager == null)
            stateManager = new ConfidentialStateManager();
        return stateManager;
    }

    @Override
    public void Op(int CID, byte[] requests, MessageContext msgCtx) {

    }

    @Override
    public void noOp(int CID, byte[][] operations, MessageContext[] msgCtx) {
        for (int i = 0; i < operations.length; i++) {
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                 ObjectOutput out = new ObjectOutputStream(bos)) {
                logger.info("NoOp in cid {} from {}", CID, msgCtx[i].getSender());
                out.write((byte) MessageType.RECONFIGURATION.ordinal());//This is wrong because after reconfiguration, old view clients' requests are delivered here
                byte[] operation = operations[i];
                out.writeInt(operation == null ? -1 : operation.length);
                out.write(operation);
                out.writeInt(-1);
                out.flush();
                bos.flush();

                logRequest(bos.toByteArray(), msgCtx[i]);
            } catch (IOException e) {
                logger.error("Failed to log noOp operation");
            }
        }
    }

    @Override
    public byte[] executeOrdered(byte[] command, byte[] privateData, MessageContext msgCtx) {
        Request request;
        if (verifyClientsRequests) {
            int hash = hashRequest(msgCtx.getSender(), msgCtx.getSession(), msgCtx.getSequence());
            request = deserializedRequests.remove(hash);
            if (request == null) {
                request = preprocessRequest(command, privateData, msgCtx.getSender());
            }
        } else {
            request = preprocessRequest(command, privateData, msgCtx.getSender());
        }
        if (request == null)
            return null;
        byte[] preprocessedCommand = request.serialize();
        byte[] response;
        if (request.getType() == MessageType.APPLICATION) {
            logger.debug("Received application ordered message of {} in CID {}. Regency: {}", msgCtx.getSender(),
                    msgCtx.getConsensusId(), msgCtx.getRegency());
            interServersCommunication.messageReceived(request.getPlainData(), msgCtx);
            response = new byte[0];
        } else if (request.getType() == MessageType.CLIENT) {
            stateLock.lock();
            ConfidentialMessage r = confidentialExecutor.appExecuteOrdered(request.getPlainData(), request.getShares(),
                    msgCtx);
            response = useTLSEncryption ? r.serialize() :
                    encryptResponse(r, msgCtx).serialize();
            stateLock.unlock();
        } else {
            logger.info("Received reconfiguration message in executeOrdered");
            response = null;
        }
        logRequest(preprocessedCommand, msgCtx);

        return response;
    }

    @Override
    public byte[] executeUnordered(byte[] command, byte[] privateData, MessageContext msgCtx) {
        Request request = preprocessRequest(command, privateData, msgCtx.getSender());
        if (request == null)
            return null;
        if (request.getType() == MessageType.APPLICATION) {
            logger.debug("Received application unordered message of {} in CID {}", msgCtx.getSender(), msgCtx.getConsensusId());
            interServersCommunication.messageReceived(request.getPlainData(), msgCtx);
            return new byte[0];
        }
        ConfidentialMessage r = confidentialExecutor.appExecuteUnordered(request.getPlainData(), request.getShares(),
                msgCtx);
        return useTLSEncryption ? r.serialize() :
                encryptResponse(r, msgCtx).serialize();
    }

    private EncryptedConfidentialMessage encryptResponse(ConfidentialMessage clearResponse, MessageContext msgCtx) {
        VerifiableShare[] clearShares = clearResponse.getShares();
        if (clearShares == null)
            return new EncryptedConfidentialMessage(clearResponse.getPlainData());

        EncryptedConfidentialData[] shares = new EncryptedConfidentialData[clearShares.length];

        for (int i = 0; i < clearShares.length; i++) {
            VerifiableShare clearCD = clearShares[i];
            EncryptedVerifiableShare encryptedVS =
                    encryptShare(msgCtx.getSender(), clearCD);

            shares[i] = new EncryptedConfidentialData(encryptedVS);
        }

        return new EncryptedConfidentialMessage(clearResponse.getPlainData(), shares);
    }

    private EncryptedVerifiableShare encryptShare(int id, VerifiableShare clearShare) {
        try {
            byte[] encryptedShare = confidentialityScheme.encryptShareFor(id,
                    clearShare.getShare());
            return new EncryptedVerifiableShare(clearShare.getShare().getShareholder(), encryptedShare, clearShare.getCommitments(),
                    clearShare.getSharedData());
        } catch (SecretSharingException e) {
            logger.error("Failed to encrypt share for client {}", id, e);
            return null;
        }
    }

    private Request preprocessRequest(byte[] commonData, byte[] privateData, int sender) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(commonData);
             ObjectInput in = new ObjectInputStream(bis)) {
            MessageType type = MessageType.getMessageType(in.read());
            Request result = null;
            int len;
            byte[] plainData = null;
            switch (type) {
                case CLIENT:
                    len = in.readInt();
                    if (len != -1) {
                        plainData = new byte[len];
                        in.readFully(plainData);
                    }
                    len = in.readInt();
                    VerifiableShare[] shares = null;
                    if (len != -1) {
                        if (len == 0) {
                            shares = new VerifiableShare[0];
                        } else if (isCombinePrivateAndCommonData) {
                            shares = new VerifiableShare[len];
                            EncryptedPublishedShares publishedShares;
                            for (int i = 0; i < len; i++) {
                                publishedShares = new EncryptedPublishedShares();
                                publishedShares.readExternal(in);
                                VerifiableShare vs = confidentialityScheme.extractShare(publishedShares);
                                shares[i] = vs;
                            }
                        } else {
                            shares = readSharesFromPrivateData(len, in, privateData);
                        }
                    }
                    result = new Request(type, plainData, shares);
                    break;
                case APPLICATION:
                    len = in.readInt();
                    plainData = new byte[len];
                    in.readFully(plainData);
                    result = new Request(type, plainData);
                    break;
                case RECONFIGURATION:
                    len = in.available();
                    plainData = new byte[len];
                    in.readFully(plainData);
                    result = new Request(type, plainData);
                    break;
            }
            return result;
        } catch (IOException | SecretSharingException | ClassNotFoundException e) {
            logger.warn("Failed to decompose request from {}", sender, e);
            return null;
        }
    }

    private VerifiableShare[] readSharesFromPrivateData(int size, ObjectInput commonDataStream, byte[] privateData) throws IOException,
            SecretSharingException, ClassNotFoundException {
        VerifiableShare[] shares = new VerifiableShare[size];

        BigInteger shareholder = confidentialityScheme.getMyShareholderId();

        try (ByteArrayInputStream privateBis = new ByteArrayInputStream(privateData);
             ObjectInput privateIn = new ObjectInputStream(privateBis)) {
            EncryptedPublishedShares publishedShares;
            for (int i = 0; i < size; i++) {
                int l = commonDataStream.readInt();
                byte[] sharedData = null;
                if (l != -1) {
                    sharedData = new byte[l];
                    commonDataStream.readFully(sharedData);
                }
                l = privateIn.readInt();
                byte[] encShare = null;
                if (l != -1) {
                    encShare = new byte[l];
                    privateIn.readFully(encShare);
                }
                Commitment commitment;
                if (isLinearCommitmentScheme)
                    commitment = CommitmentUtils.getInstance().readCommitment(commonDataStream);
                else {
                    byte[] c = new byte[commonDataStream.readInt()];
                    commonDataStream.readFully(c);
                    byte[] witness = new byte[privateIn.readInt()];
                    privateIn.readFully(witness);
                    TreeMap<Integer, byte[]> witnesses = new TreeMap<>();
                    witnesses.put(shareholder.hashCode(), witness);
                    commitment = new ConstantCommitment(c, witnesses);
                }
                Map<Integer, byte[]> encryptedShares = new HashMap<>(1);
                encryptedShares.put(processId, encShare);
                publishedShares = new EncryptedPublishedShares(
                        encryptedShares, commitment, sharedData);
                VerifiableShare vs = confidentialityScheme.extractShare(publishedShares);
                shares[i] = vs;
            }
        }
        return shares;
    }

    private void saveState(byte[] snapshot, int lastCID) {
        logLock.lock();
        logger.debug("Saving state of CID {}", lastCID);

        log.newCheckpoint(snapshot, TOMUtil.computeHash(snapshot), lastCID);

        logLock.unlock();
        logger.debug("Finished saving state of CID {}", lastCID);
    }

    private void saveCommands(byte[][] commands, MessageContext[] msgCtx) {
        if (commands.length != msgCtx.length) {
            logger.debug("----SIZE OF COMMANDS AND MESSAGE CONTEXTS IS DIFFERENT----");
            logger.debug("----COMMANDS: {}, CONTEXTS: {} ----", commands.length, msgCtx.length);
        }
        logger.debug("Saving Commands of client {} with cid {}", msgCtx[0].getSender(), msgCtx[0].getConsensusId());
        logLock.lock();

        int cid = msgCtx[0].getConsensusId();
        int batchStart = 0;
        for (int i = 0; i <= msgCtx.length; i++) {
            if (i == msgCtx.length) { // the batch command contains only one command or it is the last position of the array
                byte[][] batch = Arrays.copyOfRange(commands, batchStart, i);
                MessageContext[] batchMsgCtx = Arrays.copyOfRange(msgCtx, batchStart, i);
                log.addMessageBatch(batch, batchMsgCtx, cid);
            } else {
                if (msgCtx[i].getConsensusId() > cid) { // saves commands when the CID changes or when it is the last batch
                    byte[][] batch = Arrays.copyOfRange(commands, batchStart, i);
                    MessageContext[] batchMsgCtx = Arrays.copyOfRange(msgCtx, batchStart, i);
                    log.addMessageBatch(batch, batchMsgCtx, cid);
                    cid = msgCtx[i].getConsensusId();
                    batchStart = i;
                }
            }
        }
        logger.debug("Log size: " + log.getNumBatches());
        logLock.unlock();
    }

    private void logRequest(byte[] command, MessageContext msgCtx) {
        int cid = msgCtx.getConsensusId();
        commands.add(command);
        msgContexts.add(msgCtx);

        if (!msgCtx.isLastInBatch()) {
            //logger.debug("Not last in the batch");
            return;
        }

        if (cid > 0 && (cid % checkpointPeriod) == 0) {
            logger.info("Performing checkpoint for consensus " + cid);
            stateLock.lock();
            ConfidentialSnapshot snapshot = confidentialExecutor.getConfidentialSnapshot();
            stateLock.unlock();
            saveState(snapshot.serialize(), cid);
        } else {
            saveCommands(commands.toArray(new byte[0][]), msgContexts.toArray(new MessageContext[0]));
        }
        getStateManager().setLastCID(cid);
        commands.clear();
        msgContexts.clear();
    }

    @Override
    public void onReconfigurationRequest(TOMMessage reconfigurationRequest) {
        logger.info("onReconfigurationRequest");
        BatchReconfigurationRequest request = (BatchReconfigurationRequest)TOMUtil.getObject(reconfigurationRequest.getContent());
        int newF;
        Set<Integer> joiningServers = new HashSet<>(request.getJoiningServers().size());
        Set<Integer> leavingServers = new HashSet<>(request.getLeavingServers());

        for (String joiningServer : request.getJoiningServers()) {
            int pid = Integer.parseInt(joiningServer.split(":")[0]);
            joiningServers.add(pid);
            BigInteger shareholder = BigInteger.valueOf(pid + 1);
            try {
                confidentialityScheme.addShareholder(pid, shareholder);
            } catch (SecretSharingException e) {
                logger.error("Failed to add new server as shareholder", e);
            }
        }

        newF = request.getF();
        stateManager.setReconfigurationParameters(newF, joiningServers, leavingServers);
    }

    @Override
    public void onReconfigurationComplete(int consensusId) {
        logger.info("onReconfigurationComplete");
        logger.info("{}", stateManager.getReconfigurationParameters());
        stateManager.executeReconfiguration(consensusId);
    }

    @Override
    public void onReconfigurationFailure() {
        logger.error("Reconfiguration failed");
    }
}
