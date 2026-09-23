# Player Requests

Players can ask Jarvis for items. He does not simply hand them over: the
request is queued, an admin decides, and the player is told either way. It is
how a survival server gives its players a butler without giving them creative
mode.

---

## Asking

A player says, in chat or to Jarvis directly:

```
jarvis, can I have some iron armour?
jarvis, I need food
```

He acknowledges, records it, and tells the player it is pending. Admins
online are notified.

## Deciding

```
/jarvis requests          list what is pending, with ids
/jarvis approve <id>      approve and fulfil it
/jarvis deny <id>         deny it; the player is told
```

Or **Admin → Pending requests** in the bell menu, where each request is a
click to approve or deny.

Both need `jarvis.admin`, or operator on the mods.

Requests are held in memory. A restart loses every pending one, and a request
nobody decides on is dropped after fifteen to twenty minutes. Approving a
request from a player who is offline removes it without delivering anything,
so they have to ask again once they are back.

---

## Why it is gated

Giving items is one of the actions Jarvis treats as dangerous, along with
enchanting, game mode, teleports and console commands. Those always require
confirmation, and the ones that change the server require `jarvis.admin` as
well. An AI that can be talked into anything should not also be able to hand
out diamonds, so the decision stays with a person.

See **Dangerous actions** in
[Commands & Permissions](Commands-and-Permissions).
