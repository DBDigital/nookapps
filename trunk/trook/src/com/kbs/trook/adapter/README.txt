This is intended to be a vaguely unified way to convert various
sorts of data into what looks like "a feed."

The general idea is that all these are Async Tasks that are called
forth from the UI, and there's a set of callback methods back to the UI,
which creates the various entries in the "feed".

