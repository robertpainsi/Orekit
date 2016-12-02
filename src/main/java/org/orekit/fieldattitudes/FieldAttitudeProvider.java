/* Copyright 2002-2016 CS Systèmes d'Information
 * Licensed to CS Systèmes d'Information (CS) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * CS licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.orekit.fieldattitudes;



import org.hipparchus.RealFieldElement;
import org.orekit.attitudes.Attitude;
import org.orekit.errors.OrekitException;
import org.orekit.frames.Frame;
import org.orekit.time.FieldAbsoluteDate;
import org.orekit.utils.FieldPVCoordinatesProvider;

/** This interface represents an attitude provider model set.
 * <p>An attitude provider provides a way to compute an {@link Attitude Attitude}
 * from an date and position-velocity local provider.</p>
 * @author V&eacute;ronique Pommier-Maurussane
 */
public interface FieldAttitudeProvider<T extends RealFieldElement<T>> {

    /** Compute the attitude corresponding to an orbital state.
     * @param pvProv local position-velocity provider around current date
     * @param date current date
     * @param frame reference frame from which attitude is computed
     * @return attitude attitude on the specified date and position-velocity state
     * @throws OrekitException if attitude cannot be computed
     */
    FieldAttitude<T> getAttitude(FieldPVCoordinatesProvider<T> pvProv, FieldAbsoluteDate<T> date, Frame frame)
        throws OrekitException;

}