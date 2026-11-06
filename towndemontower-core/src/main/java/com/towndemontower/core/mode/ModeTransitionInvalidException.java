package com.towndemontower.core.mode;

import com.towndemontower.core.contract.ErrorCodes;

public class ModeTransitionInvalidException extends RuntimeException {
    public ModeTransitionInvalidException(AgentMode from, ModeEvent event) {
        super(ErrorCodes.MODE_TRANSITION_INVALID + ": " + from + " --" + event);
    }
}
