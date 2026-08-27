CC = c89
CFLAGS = -Wc,"ASM,NOXPLINK"
TARGET = safauth
SOURCE = safauth.c
JAVA_HOME = /usr/lpp/java/J8.0_64
JAVAC = $(JAVA_HOME)/bin/javac
OMVS_SOURCES = OMVSEnum.java OMVSSecurityChecks.java
JAVA_SOURCES = $(OMVS_SOURCES) \
	GhostWalker.java portscan.java
JAVA_TARGETS = OMVSEnum.class OMVSSecurityChecks.class \
	GhostWalker.class portscan.class

.PHONY: all clean java

all: $(TARGET) java

$(TARGET): $(SOURCE)
	$(CC) $(CFLAGS) $(CPPFLAGS) -o $@ $(SOURCE) $(LDFLAGS) $(LDLIBS)

java: $(JAVA_TARGETS)

OMVSEnum.class: $(OMVS_SOURCES)
	$(JAVAC) $(OMVS_SOURCES)

OMVSSecurityChecks.class: OMVSEnum.class
	@test -f $@ || $(JAVAC) $(OMVS_SOURCES)

GhostWalker.class: GhostWalker.java
	$(JAVAC) GhostWalker.java

portscan.class: portscan.java
	$(JAVAC) portscan.java

clean:
	rm -f $(TARGET) OMVSEnum*.class OMVSSecurityChecks*.class \
		GhostWalker*.class portscan*.class
