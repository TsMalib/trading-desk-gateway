package dev.belis.trader.desk.telegram;

import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.proposal.ProposalCreated;
import dev.belis.trader.desk.proposal.ProposalExpired;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.store.Instrument;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Shows proposals in Telegram: sends a new PROPOSED proposal with Approve / Reject buttons, and edits the
 * message (removing the buttons) when it is decided some other way than a button tap (expired, cancelled).
 *
 * <p>Observers run synchronously on the thread that fired the event. ProposalService fires
 * {@link ProposalCreated} after the proposal is committed and outside its propose lock, so the propose_* tool
 * returns once the owner has the message (or sending failed). Sends are bounded by the REST client timeouts.
 * A proposal whose message could not be sent simply expires.
 */
@ApplicationScoped
public class ProposalNotifier {

    private static final Logger LOG = Logger.getLogger(ProposalNotifier.class);

    private final TelegramClient telegram;
    private final ProposalService proposals;
    private final ProposalMessages messages;

    public ProposalNotifier(TelegramClient telegram, ProposalService proposals, DeskConfig config) {
        this.telegram = telegram;
        this.proposals = proposals;
        this.messages = new ProposalMessages(config.mode());
    }

    void onCreated(@Observes ProposalCreated e) {
        send(e.proposalId());
    }

    void onExpired(@Observes ProposalExpired e) {
        refresh(e.proposalId());
    }

    /** Sends proposal {@code id} with its buttons and stores the message id. Never throws. */
    public void send(long id) {
        try {
            Proposal p = proposals.find(id).orElseThrow();
            Instrument i = proposals.instrument(p.conid).orElse(null);
            long messageId = telegram.send(messages.proposal(p, i, position(p)), ProposalMessages.keyboard(id));
            proposals.linkTelegramMessage(id, messageId);
        } catch (RuntimeException e) {
            LOG.errorf("Could not send proposal %d to Telegram: %s", id, e.getMessage());
        }
    }

    /**
     * Rewrites the message of proposal {@code id} to show its current status, without buttons. Does nothing
     * when it was never sent. Never throws.
     */
    public void refresh(long id) {
        try {
            Proposal p = proposals.find(id).orElse(null);
            if (p == null || p.telegramMsgId == null) {
                return;
            }
            Instrument i = proposals.instrument(p.conid).orElse(null);
            telegram.edit(p.telegramMsgId, messages.decided(p, i, position(p)), null);
        } catch (RuntimeException e) {
            LOG.warnf("Could not update the Telegram message of proposal %d: %s", id, e.getMessage());
        }
    }

    Optional<PositionMeta> position(Proposal p) {
        if (p.kind == ProposalKind.ENTRY || p.conid == null) {
            return Optional.empty();
        }
        return proposals.positionMeta(p.conid);
    }

    ProposalMessages messages() {
        return messages;
    }
}
