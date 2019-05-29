Reflect about your solution!

Summary:


Tom Tucek, 1325775

I've implemented all of the assigned features and tried to keep implementations as simple as possible. 
Most of the output for testing has been commented out, but can still be found in the source code.

I've had to improvise a little when it came to client-server communication via commands (!lookup especially), as I have one main thread running on each client, displaying all incoming messages from the server via shell. 
All messages starting with an exclamation mark (!) are filtered out though, and either still displayed (!sm = server message), but not saved as last received message, or processed otherwise.

I've tested mostly manually but I also wrote a little testfile for the given test classes, which can be found within the source folders.
The testfile of mine fails some tests most of the time though, as the "!ack"-response is often received too late to be caught by the verification.

Other things:
The last received message and all lookup'd users are deleted on logout.
Clients can also register hostnames (eg localhost:1234) instead of IP addresses with !register.
Registered clients are able to send private messages to themselves. 
I did not modify the shell-class (or any class other than Client and Chatserver), so wrong input still shows the stacktrace of the shell's exception.

