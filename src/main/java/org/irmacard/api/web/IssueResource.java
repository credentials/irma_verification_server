package org.irmacard.api.web;

import org.irmacard.api.common.CredentialRequest;
import org.irmacard.api.common.IdentityProviderRequest;
import org.irmacard.api.common.IssuingRequest;
import org.irmacard.api.web.exceptions.InputInvalidException;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixIssuer;
import org.irmacard.credentials.idemix.IdemixSecretKey;
import org.irmacard.credentials.idemix.info.IdemixKeyStore;
import org.irmacard.credentials.idemix.messages.IssueCommitmentMessage;
import org.irmacard.credentials.idemix.messages.IssueSignatureMessage;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.credentials.idemix.util.Crypto;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.info.IssuerDescription;
import org.irmacard.api.common.DisclosureProofRequest;
import org.irmacard.api.common.ClientQr;
import org.irmacard.api.common.util.GsonUtil;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.math.BigInteger;
import java.util.*;

@Path("issue")
public class IssueResource {
	private Sessions<IssueSession> sessions = Sessions.getIssuingSessions();

	@Inject
	public IssueResource() {}

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public ClientQr create(IdentityProviderRequest isRequest) throws InfoException {
		IssuingRequest request = isRequest.getRequest();

		if (request == null || request.getCredentials() == null || request.getCredentials().size() == 0)
			throw new InputInvalidException("Incomplete request");

		// Check if we have all necessary secret keys
		for (CredentialRequest cred : request.getCredentials()) {
			IssuerDescription id = DescriptionStore.getInstance().getIssuerDescription(cred.getIssuerName());
			IdemixKeyStore.getInstance().getSecretKey(id); // Throws InfoException if we don't have it, TODO handle better
		}

		request.setNonceAndContext();

		String token = Sessions.generateSessionToken();
		IssueSession session = new IssueSession(token, isRequest);
		sessions.addSession(session);

		System.out.println("Received issue session, token: " + token);
		System.out.println(GsonUtil.getGson().toJson(isRequest));

		return new ClientQr("2.0", token);
	}

	@GET
	@Path("/{sessiontoken}")
	@Produces(MediaType.APPLICATION_JSON)
	public IssuingRequest get(@PathParam("sessiontoken") String sessiontoken) {
		IssueSession session = sessions.getNonNullSession(sessiontoken);
		if (session.getStatus() != IssueSession.Status.INITIALIZED) {
			throw new WebApplicationException(Response.Status.UNAUTHORIZED);
		}

		System.out.println("Received get, token: " + sessiontoken);

		session.setStatusConnected();
		return session.getRequest();
	}

	@POST
	@Path("/{sessiontoken}/commitments")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public ArrayList<IssueSignatureMessage> getSignatureMessages(IssueCommitmentMessage commitments,
			@PathParam("sessiontoken") String sessiontoken) throws InfoException, CredentialsException {
		IssueSession session = sessions.getNonNullSession(sessiontoken);
		if (session.getStatus() != IssueSession.Status.CONNECTED) {
			throw new WebApplicationException(Response.Status.UNAUTHORIZED);
		}

		System.out.println("Received commitments, token: " + sessiontoken);

		IssuingRequest request = session.getRequest();
		ProofList proofs = commitments.getCombinedProofs();
		int credcount = request.getCredentials().size();
		if (proofs.size() < credcount)
			throw new InputInvalidException("Proof count does not match credential count");

		// Lookup the public keys of any ProofD's in the proof list
		proofs.populatePublicKeyArray();

		// Lookup the public keys of all ProofU's in the proof list. We have to do this before we can compute the CL
		// sigatures below, because that also verifies the proofs, which needs these keys.
		ArrayList<IssueSignatureMessage> sigs = new ArrayList<>(credcount);
		for (int i = 0; i < credcount; i++) {
			CredentialRequest cred = request.getCredentials().get(i);
			proofs.setPublicKey(i, cred.getPublicKey());
		}

		// Construct the CL signature for each credential to be issued.
		// FIXME This also checks the validity of _all_ proofs, for each iteration - so more than once
		for (int i = 0; i < credcount; i++) {
			CredentialRequest cred = request.getCredentials().get(i);
			IdemixSecretKey sk = IdemixKeyStore.getInstance().getSecretKey(cred.getIssuerDescription());

			IdemixIssuer issuer = new IdemixIssuer(cred.getPublicKey(), sk, request.getContext());
			sigs.add(issuer.issueSignature(
					commitments, cred.convertToBigIntegers(), i, request.getNonce()));
		}

		session.setStatusDone();
		return sigs;
	}
}
