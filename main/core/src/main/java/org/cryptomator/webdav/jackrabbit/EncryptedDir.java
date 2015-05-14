/*******************************************************************************
 * Copyright (c) 2014 Sebastian Stenzel
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 * 
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 ******************************************************************************/
package org.cryptomator.webdav.jackrabbit;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jackrabbit.webdav.DavException;
import org.apache.jackrabbit.webdav.DavResource;
import org.apache.jackrabbit.webdav.DavResourceIterator;
import org.apache.jackrabbit.webdav.DavResourceIteratorImpl;
import org.apache.jackrabbit.webdav.DavResourceLocator;
import org.apache.jackrabbit.webdav.DavServletResponse;
import org.apache.jackrabbit.webdav.DavSession;
import org.apache.jackrabbit.webdav.io.InputContext;
import org.apache.jackrabbit.webdav.io.OutputContext;
import org.apache.jackrabbit.webdav.lock.LockManager;
import org.apache.jackrabbit.webdav.property.DavPropertyName;
import org.apache.jackrabbit.webdav.property.DefaultDavProperty;
import org.apache.jackrabbit.webdav.property.ResourceType;
import org.cryptomator.crypto.Cryptor;
import org.cryptomator.crypto.exceptions.CounterOverflowException;
import org.cryptomator.crypto.exceptions.DecryptFailedException;
import org.cryptomator.crypto.exceptions.EncryptFailedException;
import org.cryptomator.webdav.exceptions.DavRuntimeException;
import org.cryptomator.webdav.exceptions.IORuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class EncryptedDir extends AbstractEncryptedNode {

	private static final Logger LOG = LoggerFactory.getLogger(EncryptedDir.class);
	private final Path directoryPath;

	public EncryptedDir(CryptoResourceFactory factory, DavResourceLocator locator, DavSession session, LockManager lockManager, Cryptor cryptor, Path directoryPath) {
		super(factory, locator, session, lockManager, cryptor);
		if (directoryPath == null || !Files.isDirectory(directoryPath)) {
			throw new IllegalArgumentException("directoryPath must be an existing directory, but was " + directoryPath);
		}
		this.directoryPath = directoryPath;
		determineProperties();
	}

	@Override
	protected Path getPhysicalPath() {
		return directoryPath;
	}

	@Override
	public boolean isCollection() {
		return true;
	}

	@Override
	public boolean exists() {
		assert Files.isDirectory(directoryPath);
		return true;
	}

	@Override
	public long getModificationTime() {
		try {
			return Files.getLastModifiedTime(directoryPath).toMillis();
		} catch (IOException e) {
			return -1;
		}
	}

	@Override
	public void addMember(DavResource resource, InputContext inputContext) throws DavException {
		if (resource instanceof AbstractEncryptedNode) {
			addMember((AbstractEncryptedNode) resource, inputContext);
		} else {
			throw new IllegalArgumentException("Unsupported resource type: " + resource.getClass().getName());
		}
	}

	private void addMember(AbstractEncryptedNode childResource, InputContext inputContext) throws DavException {
		if (childResource.isCollection()) {
			this.addMemberDir(childResource.getLocator(), inputContext);
		} else {
			this.addMemberFile(childResource.getLocator(), inputContext);
		}
	}

	private void addMemberDir(DavResourceLocator childLocator, InputContext inputContext) throws DavException {
		try {
			// the following invokation will create nonexisting directories:
			factory.getEncryptedDirectoryPath(childLocator.getResourcePath());
		} catch (SecurityException e) {
			throw new DavException(DavServletResponse.SC_FORBIDDEN, e);
		}
	}

	private void addMemberFile(DavResourceLocator childLocator, InputContext inputContext) throws DavException {
		final Path filePath = factory.getEncryptedFilePath(childLocator.getResourcePath());
		try (final SeekableByteChannel channel = Files.newByteChannel(filePath, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
			cryptor.encryptFile(inputContext.getInputStream(), channel);
		} catch (SecurityException e) {
			throw new DavException(DavServletResponse.SC_FORBIDDEN, e);
		} catch (IOException e) {
			LOG.error("Failed to create file.", e);
			throw new IORuntimeException(e);
		} catch (CounterOverflowException e) {
			// lets indicate this to the client as a "file too big" error
			throw new DavException(DavServletResponse.SC_INSUFFICIENT_SPACE_ON_RESOURCE, e);
		} catch (EncryptFailedException e) {
			LOG.error("Encryption failed for unknown reasons.", e);
			throw new IllegalStateException("Encryption failed for unknown reasons.", e);
		} finally {
			IOUtils.closeQuietly(inputContext.getInputStream());
		}
	}

	@Override
	public DavResourceIterator getMembers() {
		try {
			final DirectoryStream<Path> directoryStream = Files.newDirectoryStream(directoryPath, cryptor.getPayloadFilesFilter());
			final List<DavResource> result = new ArrayList<>();

			for (final Path childPath : directoryStream) {
				try {
					final String cleartextFilename = cryptor.decryptFilename(childPath.getFileName().toString(), factory);
					final String cleartextFilepath = FilenameUtils.concat(getResourcePath(), cleartextFilename);
					final DavResourceLocator childLocator = locator.getFactory().createResourceLocator(locator.getPrefix(), locator.getWorkspacePath(), cleartextFilepath);
					final DavResource resource = factory.createResource(childLocator, session);
					result.add(resource);
				} catch (DecryptFailedException e) {
					LOG.warn("Decryption of resource failed: " + childPath);
					continue;
				}
			}
			return new DavResourceIteratorImpl(result);
		} catch (IOException e) {
			LOG.error("Exception during getMembers.", e);
			throw new IORuntimeException(e);
		} catch (DavException e) {
			LOG.error("Exception during getMembers.", e);
			throw new DavRuntimeException(e);
		}
	}

	@Override
	public void removeMember(DavResource member) throws DavException {
		if (member instanceof AbstractEncryptedNode) {
			removeMember((AbstractEncryptedNode) member);
		} else {
			throw new IllegalArgumentException("Unsupported resource type: " + member.getClass().getName());
		}
	}

	private void removeMember(AbstractEncryptedNode member) throws DavException {
		try {
			if (member.isCollection()) {
				// remove sub-members recursively before deleting own directory
				for (Iterator<DavResource> iterator = member.getMembers(); iterator.hasNext();) {
					DavResource m = iterator.next();
					member.removeMember(m);
				}
				final Path memberDirectoryPath = factory.getEncryptedDirectoryPath(member.getResourcePath());
				Files.deleteIfExists(memberDirectoryPath);
			}
			final Path memberPath = factory.getEncryptedFilePath(member.getResourcePath());
			Files.deleteIfExists(memberPath);
		} catch (FileNotFoundException e) {
			// no-op
		} catch (IOException e) {
			throw new IORuntimeException(e);
		}
	}

	@Override
	public void move(AbstractEncryptedNode dest) throws DavException, IOException {
		throw new UnsupportedOperationException("not yet implemented");
		// final Path srcDir = this.locator.getEncryptedDirectoryPath(false);
		// final Path dstDir = dest.locator.getEncryptedDirectoryPath(true);
		// final Path srcFile = this.locator.getEncryptedFilePath();
		// final Path dstFile = dest.locator.getEncryptedFilePath();
		//
		// // check for conflicts:
		// if (Files.exists(dstDir) && Files.getLastModifiedTime(dstDir).toMillis() > Files.getLastModifiedTime(dstDir).toMillis()) {
		// throw new DavException(DavServletResponse.SC_CONFLICT, "Directory at destination already exists: " + dstDir.toString());
		// }
		//
		// // move:
		// Files.createDirectories(dstDir);
		// try {
		// Files.move(srcDir, dstDir, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		// Files.move(srcFile, dstFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		// } catch (AtomicMoveNotSupportedException e) {
		// Files.move(srcDir, dstDir, StandardCopyOption.REPLACE_EXISTING);
		// Files.move(srcFile, dstFile, StandardCopyOption.REPLACE_EXISTING);
		// }
	}

	@Override
	public void copy(AbstractEncryptedNode dest, boolean shallow) throws DavException, IOException {
		throw new UnsupportedOperationException("not yet implemented");
		// final Path srcDir = this.locator.getEncryptedDirectoryPath(false);
		// final Path dstDir = dest.locator.getEncryptedDirectoryPath(true);
		// final Path srcFile = this.locator.getEncryptedFilePath();
		// final Path dstFile = dest.locator.getEncryptedFilePath();
		//
		// // check for conflicts:
		// if (Files.exists(dstDir) && Files.getLastModifiedTime(dstDir).toMillis() > Files.getLastModifiedTime(dstDir).toMillis()) {
		// throw new DavException(DavServletResponse.SC_CONFLICT, "Directory at destination already exists: " + dstDir.toString());
		// }
		//
		// // copy:
		// Files.createDirectories(dstDir);
		// try {
		// Files.copy(srcDir, dstDir, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		// Files.copy(srcFile, dstFile, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		// } catch (AtomicMoveNotSupportedException e) {
		// Files.copy(srcDir, dstDir, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
		// Files.copy(srcFile, dstFile, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
		// }
	}

	@Override
	public void spool(OutputContext outputContext) throws IOException {
		// do nothing
	}

	@Override
	protected void determineProperties() {
		properties.add(new ResourceType(ResourceType.COLLECTION));
		properties.add(new DefaultDavProperty<Integer>(DavPropertyName.ISCOLLECTION, 1));
		try {
			final BasicFileAttributes attrs = Files.readAttributes(directoryPath, BasicFileAttributes.class);
			properties.add(new DefaultDavProperty<String>(DavPropertyName.CREATIONDATE, FileTimeUtils.toRfc1123String(attrs.creationTime())));
			properties.add(new DefaultDavProperty<String>(DavPropertyName.GETLASTMODIFIED, FileTimeUtils.toRfc1123String(attrs.lastModifiedTime())));
		} catch (IOException e) {
			LOG.error("Error determining metadata " + directoryPath.toString(), e);
			// don't add any further properties
		}
	}

}
