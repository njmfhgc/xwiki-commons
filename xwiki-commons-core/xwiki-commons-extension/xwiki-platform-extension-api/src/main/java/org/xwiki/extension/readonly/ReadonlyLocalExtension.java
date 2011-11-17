/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.extension.readonly;

import java.io.File;

import org.xwiki.extension.LocalExtension;
import org.xwiki.extension.repository.ExtensionRepository;
import org.xwiki.extension.wrap.WrappingLocalExtension;

/**
 * Provide a readonly access to a local extension.
 * 
 * @param <T> the extension type
 * @version $Id$
 */
public class ReadonlyLocalExtension<T extends LocalExtension> extends WrappingLocalExtension<T> implements
    LocalExtension
{
    /**
     * @param localExtension the wrapped local extension
     */
    public ReadonlyLocalExtension(T localExtension)
    {
        super(localExtension);
    }

    // Extension

    @Override
    public ExtensionRepository getRepository()
    {
        return ReadonlyUtils.unmodifiableExtensionRepository(super.getRepository());
    }

    // LocalExtension

    @Override
    public File getFile()
    {
        return getExtension().getFile();
    }

}
