# Unix Enumeration Tools

This folder contains various tools used to enumeration unix system services
on z/OS. 

## ALL.sh

This script generates JCL which will automatically upload, compile (if needed),
and run the various programs in this folder. 

Note: portscan is compiled but not run. 

## FileSystemTraversal

This java program will search the file system and output any files the current
account has write access to.  E.g. `java FileSystemTraversal /u`

```
Usage: java FileSystemTraversal [flags] <directory_path>
Flags:
  -d (include directories)
  -x (only executable files)
  -w (only writable files)
  -r (only readable files)
  -a (both -d and -x)
  -f <filename> (output to file)
```

## OMVSEnum.sh

A script similar to LinEnum.sh but looks for common issues in z/OS Unix System
Services

You can run it with `./OMVSEnum.sh` it also allowes for thorough searches and
specifying a keyword to search within files your account has read access
to. 

## portscan.java

This is essentially a SYN packet sprayer. By itself it can be used to check for openports on other hosts. But when paired with Egressbuster it allows us to find potentially open egress ports from the mainframe to our Linux machine. On the linux machine download and run https://github.com/trustedsec/egressbuster/blob/master/egress_listener.py with the following: `python egress_listener.py 0.0.0.0 enps60 0.0.0.0/0` where 0.0.0.0 is the IP address to listen on, enps60 is the interface (you can get both from `ip -c a`) and 0.0.0.0/0 is what IP address we accept, in this case all. 

On the mainframe, in USS/OMVS run the following:

1. `javac portscan.java`
2. `java -cp . portscan host, start port, end port, [-t timeout] [-d debug]` where `-t timeout` is the timeout in miliseconds, 1000 is the default, and `-d` is optional debug messaging. 

Observe for connections in the Linux terminal, there will be no confirmation in the mainframe terminal, unless the packets come back in a reasonable amount of time, if you used `-d` then you'll see the packets sent out. 
