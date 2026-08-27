CC = c89
CFLAGS = -Wc,"ASM,NOXPLINK"
TARGET = safauth
SOURCE = safauth.c
JAVA_HOME = /usr/lpp/java/J8.0_64
JAVAC = $(JAVA_HOME)/bin/javac
JAR = $(JAVA_HOME)/bin/jar
OMVS_SOURCES = OMVSEnum.java OMVSSecurityChecks.java
JAVA_SOURCES = $(OMVS_SOURCES) \
	GhostWalker.java portscan.java
JAVA_JARS = OMVSEnum.jar GhostWalker.jar portscan.jar
BUILD_DIR = build
OMVS_BUILD = $(BUILD_DIR)/omvsenum
GHOST_BUILD = $(BUILD_DIR)/ghostwalker
PORT_BUILD = $(BUILD_DIR)/portscan
JAVA_TARGETS = $(OMVS_BUILD)/.built $(GHOST_BUILD)/.built \
	$(PORT_BUILD)/.built

.PHONY: all clean jars java

all: $(TARGET) jars

$(TARGET): $(SOURCE)
	$(CC) $(CFLAGS) $(CPPFLAGS) -o $@ $(SOURCE) $(LDFLAGS) $(LDLIBS)

java: $(JAVA_TARGETS)

jars: $(JAVA_JARS)

$(OMVS_BUILD)/.built: $(OMVS_SOURCES)
	mkdir -p $(OMVS_BUILD)
	$(JAVAC) -d $(OMVS_BUILD) $(OMVS_SOURCES)
	touch $@

$(GHOST_BUILD)/.built: GhostWalker.java
	mkdir -p $(GHOST_BUILD)
	$(JAVAC) -d $(GHOST_BUILD) GhostWalker.java
	touch $@

$(PORT_BUILD)/.built: portscan.java
	mkdir -p $(PORT_BUILD)
	$(JAVAC) -d $(PORT_BUILD) portscan.java
	touch $@

OMVSEnum.jar: $(OMVS_BUILD)/.built
	$(JAR) cfe $@ OMVSEnum -C $(OMVS_BUILD) .

GhostWalker.jar: $(GHOST_BUILD)/.built
	$(JAR) cfe $@ GhostWalker -C $(GHOST_BUILD) .

portscan.jar: $(PORT_BUILD)/.built
	$(JAR) cfe $@ portscan -C $(PORT_BUILD) .

clean:
	rm -f $(TARGET) $(JAVA_JARS) \
		OMVSEnum*.class OMVSSecurityChecks*.class \
		GhostWalker*.class portscan*.class
	rm -rf $(BUILD_DIR)
