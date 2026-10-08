package com.rootstock.core.tools;

/**
 * A tool as Rootstock knows it: the logical name steps use, and where it really
 * lives. The logical and remote names are usually the same; keeping them apart
 * lets a pack map Rootstock's name onto whatever a client system calls it.
 *
 * @param name       logical name used by nodes and allowlists, e.g. {@code find_household}
 * @param connection which client system serves it
 * @param remoteName the tool's name on that system
 * @param access     READ or WRITE: decides the token, and where the tool may be allowed
 */
public record ToolDefinition(String name, String connection, String remoteName, ToolAccess access) {
}
