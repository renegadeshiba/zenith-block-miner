package com.zenithplugins.blockminer.module;

/** The bot disconnected or a player took over partway through an errand. */
final class ErrandAborted extends RuntimeException {

    ErrandAborted() {
        super("The bot disconnected or a player took over");
    }
}
