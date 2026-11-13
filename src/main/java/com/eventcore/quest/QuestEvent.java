package com.eventcore.quest;

import com.eventcore.EventCorePlugin;
import com.eventcore.event.EventManager.ActiveEvent;
import com.eventcore.event.GameEvent;

/** The EventCore side of the quest event: start / stop / timer. Everything else lives in {@link QuestManager}. */
public final class QuestEvent extends GameEvent {
    public QuestEvent(EventCorePlugin pl) { super(pl, "quest"); }

    @Override public int minMinutes() { return 1; }
    @Override public void onStart(ActiveEvent ae) { pl.quest.onEventStart(ae); }
    @Override public void onStop(ActiveEvent ae) { pl.quest.onEventStop(ae); }
    @Override public void onSecond(ActiveEvent ae, int secondsLeft) { pl.quest.onEventSecond(ae); }
}
