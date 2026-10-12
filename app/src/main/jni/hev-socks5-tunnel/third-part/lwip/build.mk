# Build

rwildcard=$(foreach d,$(wildcard $1*), \
          $(call rwildcard,$d/,$2) \
          $(filter $(subst *,%,$2),$d))

# Exclude Unix-specific ports that are not needed for Android
SRCFILES=$(filter-out $(SRCDIR)/ports/unix/%,$(call rwildcard,$(SRCDIR)/,*.c *.S))
