/* Copyright 2002-2008 CS Communication & Systèmes
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.cs.orekit.bodies;

import org.apache.commons.math.geometry.Vector3D;

import fr.cs.orekit.errors.OrekitException;
import fr.cs.orekit.frames.Frame;
import fr.cs.orekit.time.AbsoluteDate;

/** Interface for celestial bodies like Sun or Moon.
 * @author Luc Maisonobe
 * @version $Revision$ $Date$
 */
public interface CelestialBody {

    /** Get the position of the body in the selected frame.
     * @param date current date
     * @param frame the frame where to define the position
     * @return position of the body (m)
     * @exception OrekitException if position cannot be computed in given frame
     */
    Vector3D getPosition(AbsoluteDate date, Frame frame) throws OrekitException;

}
