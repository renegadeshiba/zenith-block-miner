package com.zenithplugins.blockminer.module;

/** The bot disconnected, a player took over, or a fight broke out, partway through an errand. */
final class ErrandAborted extends RuntimeException {

    ErrandAborted() {
        super("The bot disconnected or a player took over");
    }

    ErrandAborted(String why) {
        super(why);
    }
}
