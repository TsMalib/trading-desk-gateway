package dev.belis.trader.desk.proposal;

/**
 * The request could not be turned into a proposal at all (invalid input, unknown symbol, broker unreachable).
 * Nothing is stored except an audit row. The message is written for the caller (an LLM) to read.
 */
public class ProposalRefusedException extends RuntimeException {

    public ProposalRefusedException(String message) {
        super(message);
    }

    public ProposalRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
