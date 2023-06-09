package confidential.benchmark;

import bftsmart.tom.core.messages.TOMMessage;
import confidential.ExtractedResponse;
import confidential.client.ServersResponseHandler;
import confidential.encrypted.EncryptedConfidentialData;
import confidential.encrypted.EncryptedConfidentialMessage;
import confidential.encrypted.EncryptedVerifiableShare;
import vss.commitment.Commitment;
import vss.facade.Mode;
import vss.facade.SecretSharingException;
import vss.secretsharing.OpenPublishedShares;
import vss.secretsharing.Share;

import java.math.BigInteger;
import java.util.*;

/**
 * @author Robin
 */
public class PreComputedEncryptedServersResponseHandler extends ServersResponseHandler {
    private final Map<byte[], EncryptedConfidentialMessage> responses;
    private final Map<EncryptedConfidentialMessage, Integer> responseHashes;
    private final int clientId;
    private boolean preComputed;

    public PreComputedEncryptedServersResponseHandler(int clientId) {
        this.clientId = clientId;
        responses = new HashMap<>();
        responseHashes = new HashMap<>();
    }

    public void setPreComputed(boolean preComputed) {
        this.preComputed = preComputed;
    }

    @Override
    public TOMMessage extractResponse(TOMMessage[] replies, int sameContent, int lastReceived) {
        if (preComputed)
            return replies[lastReceived];
        EncryptedConfidentialMessage response;
        Map<Integer, LinkedList<EncryptedConfidentialMessage>> msgs = new HashMap<>();
        for (TOMMessage msg : replies) {
            if (msg == null)
                continue;
            response = responses.get(msg.getContent());
            if (response == null) {
                logger.warn("Something went wrong while getting deserialized response from {}", msg.getSender());
                continue;
            }
            int responseHash = responseHashes.get(response);

            LinkedList<EncryptedConfidentialMessage> msgList = msgs.computeIfAbsent(responseHash, k -> new LinkedList<>());
            msgList.add(response);
        }

        for (LinkedList<EncryptedConfidentialMessage> msgList : msgs.values()) {
            if (msgList.size() == sameContent) {
                EncryptedConfidentialMessage firstMsg = msgList.getFirst();
                byte[] plainData = firstMsg.getPlainData();
                byte[][] confidentialData = null;

                if (firstMsg.getShares() != null) { // this response has secret data
                    int numSecrets = firstMsg.getShares().length;
                    ArrayList<LinkedList<EncryptedVerifiableShare>> verifiableShares =
                            new ArrayList<>(numSecrets);
                    for (int i = 0; i < numSecrets; i++) {
                        verifiableShares.add(new LinkedList<>());
                    }
                    confidentialData = new byte[numSecrets][];

                    for (EncryptedConfidentialMessage confidentialMessage : msgList) {
                        EncryptedConfidentialData[] sharesI =
                                confidentialMessage.getShares();
                        for (int i = 0; i < numSecrets; i++) {
                            verifiableShares.get(i).add(sharesI[i].getShare());
                        }
                    }

                    byte[] shareData;
                    Share[] shares;
                    for (int i = 0; i < numSecrets; i++) {
                        LinkedList<EncryptedVerifiableShare> secretI = verifiableShares.get(i);
                        shares = new Share[secretI.size()];
                        Map<BigInteger, Commitment> commitmentsToCombine =
                                new HashMap<>(secretI.size());
                        shareData = secretI.getFirst().getSharedData();//check if the majority of servers sent the same shared data
                        int k = 0;
                        for (EncryptedVerifiableShare verifiableShare : secretI) {
                            try {
                                shares[k] = new Share(verifiableShare.getShareholder(),
                                        confidentialityScheme.decryptShareFor(clientId, verifiableShare.getShare()));
                            } catch (SecretSharingException e) {
                                logger.error("Failed to decrypt share of {}",
                                        verifiableShare.getShareholder(), e);
                            }
                            commitmentsToCombine.put(
                                    verifiableShare.getShareholder(),
                                    verifiableShare.getCommitments());
                            k++;
                        }
                        Commitment commitment =
                                commitmentScheme.combineCommitments(commitmentsToCombine);
                        OpenPublishedShares secret = new OpenPublishedShares(shares, commitment, shareData);
                        try {
                            confidentialData[i] = confidentialityScheme.combine(secret,
                                    shareData == null ? Mode.SMALL_SECRET : Mode.LARGE_SECRET);
                        } catch (SecretSharingException e) {
                            ExtractedResponse extractedResponse = new ExtractedResponse(plainData, confidentialData, e);
                            TOMMessage lastMsg = replies[lastReceived];
                            return new TOMMessage(lastMsg.getSender(),
                                    lastMsg.getSession(), lastMsg.getSequence(),
                                    lastMsg.getOperationId(), extractedResponse.serialize(), new byte[0],
                                    lastMsg.getViewID(), lastMsg.getReqType());
                        }
                    }
                }
                ExtractedResponse extractedResponse = new ExtractedResponse(plainData, confidentialData);
                TOMMessage lastMsg = replies[lastReceived];
                return new TOMMessage(lastMsg.getSender(),
                        lastMsg.getSession(), lastMsg.getSequence(),
                        lastMsg.getOperationId(), extractedResponse.serialize(), new byte[0],
                        lastMsg.getViewID(), lastMsg.getReqType());

            }
        }
        logger.error("This should not happen. Did not found {} equivalent responses", sameContent);
        return null;
    }

    @Override
    public int compare(byte[] o1, byte[] o2) {
        if (o1 == null && o2 == null)
            return 0;
        EncryptedConfidentialMessage response1 = responses.computeIfAbsent(o1,
                EncryptedConfidentialMessage::deserialize);
        EncryptedConfidentialMessage response2 = responses.computeIfAbsent(o2, EncryptedConfidentialMessage::deserialize);
        if (response1 == null && response2 == null)
            return 0;
        if (response1 == null)
            return 1;
        if (response2 == null)
            return -1;
        int hash1 = responseHashes.computeIfAbsent(response1, EncryptedConfidentialMessage::hashCode);
        int hash2 = responseHashes.computeIfAbsent(response2, EncryptedConfidentialMessage::hashCode);
        return hash1 - hash2;
    }

    @Override
    public void reset() {
        responses.clear();
        responseHashes.clear();
    }
}
