/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.values;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.coders.CannotProvideCoderException.ReasonCode;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.transforms.AppliedPTransform;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;

/**
 * A {@link TypedPValue TypedPValue&lt;T&gt;} is the abstract base class of things that
 * store some number of values of type {@code T}.
 *
 * <p>Because we know the type {@code T}, this is the layer of the inheritance hierarchy where
 * we store a coder for objects of type {@code T}.
 *
 * @param <T> the type of the values stored in this {@link TypedPValue}
 */
public abstract class TypedPValue<T> extends PValueBase implements PValue {

  /**
   * Returns the {@link Coder} used by this {@link TypedPValue} to encode and decode
   * the values stored in it.
   *
   * @throws IllegalStateException if the {@link Coder} hasn't been set, and
   * couldn't be inferred.
   */
  public Coder<T> getCoder() {
    if (coder == null) {
        coder = inferCoderOrFail();
    }
    return coder;
  }

  /**
   * Sets the {@link Coder} used by this {@link TypedPValue} to encode and decode the
   * values stored in it. Returns {@code this}.
   *
   * @throws IllegalStateException if this {@link TypedPValue} has already
   * been finalized and is no longer settable, e.g., by having
   * {@code apply()} called on it
   */
  public TypedPValue<T> setCoder(Coder<T> coder) {
    if (isFinishedSpecifyingInternal()) {
      throw new IllegalStateException(
          "cannot change the Coder of " + this + " once it's been used");
    }
    if (coder == null) {
      throw new IllegalArgumentException(
          "Cannot setCoder(null)");
    }
    this.coder = coder;
    return this;
  }

  /**
   * After building, finalizes this {@link PValue} to make it ready for
   * running.  Automatically invoked whenever the {@link PValue} is "used"
   * (e.g., when apply() is called on it) and when the Pipeline is
   * run (useful if this is a {@link PValue} with no consumers).
   */
  @Override
  public void finishSpecifying() {
    if (isFinishedSpecifyingInternal()) {
      return;
    }
    super.finishSpecifying();
    // Ensure that this TypedPValue has a coder by inferring the coder if none exists; If not,
    // this will throw an exception.
    getCoder();
  }

  /////////////////////////////////////////////////////////////////////////////
  // Internal details below here.

  /**
   * The {@link Coder} used by this {@link TypedPValue} to encode and decode the
   * values stored in it, or null if not specified nor inferred yet.
   */
  private Coder<T> coder;

  protected TypedPValue(Pipeline p) {
    super(p);
  }

  private TypeDescriptor<T> typeDescriptor;

  /**
   * Returns a {@link TypeDescriptor TypeDescriptor&lt;T&gt;} with some reflective information
   * about {@code T}, if possible. May return {@code null} if no information
   * is available. Subclasses may override this to enable better
   * {@code Coder} inference.
   */
  public TypeDescriptor<T> getTypeDescriptor() {
    return typeDescriptor;
  }

  /**
   * Sets the {@link TypeDescriptor TypeDescriptor&lt;T&gt;} associated with this class. Better
   * reflective type information will lead to better {@link Coder}
   * inference.
   */
  public TypedPValue<T> setTypeDescriptorInternal(TypeDescriptor<T> typeDescriptor) {
    this.typeDescriptor = typeDescriptor;
    return this;
  }

  /**
   * If the coder is not explicitly set, this sets the coder for
   * this {@link TypedPValue} to the best coder that can be inferred
   * based upon the known {@link TypeDescriptor}. By default, this is null,
   * but can and should be improved by subclasses.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private Coder<T> inferCoderOrFail() {
    // First option for a coder: use the Coder set on this PValue.
    if (coder != null) {
      return coder;
    }

    AppliedPTransform<?, ?, ?> application = getProducingTransformInternal();

    // Second option for a coder: Look in the coder registry.
    CoderRegistry registry = getPipeline().getCoderRegistry();
    TypeDescriptor<T> token = getTypeDescriptor();
    CannotProvideCoderException inferFromTokenException = null;
    if (token != null) {
      try {
          return registry.getDefaultCoder(token);
      } catch (CannotProvideCoderException exc) {
        inferFromTokenException = exc;
        // Attempt to detect when the token came from a TupleTag used for a ParDo side output,
        // and provide a better error message if so. Unfortunately, this information is not
        // directly available from the TypeDescriptor, so infer based on the type of the PTransform
        // and the error message itself.
        if (application.getTransform() instanceof ParDo.BoundMulti
            && exc.getReason() == ReasonCode.TYPE_ERASURE) {
          inferFromTokenException = new CannotProvideCoderException(exc.getMessage()
              + " If this error occurs for a side output of the producing ParDo, verify that the "
              + "TupleTag for this output is constructed with proper type information (see "
              + "TupleTag Javadoc) or explicitly set the Coder to use if this is not possible.");
        }
      }
    }

    // Third option for a coder: use the default Coder from the producing PTransform.
    CannotProvideCoderException inputCoderException;
    try {
      return ((PTransform) application.getTransform()).getDefaultOutputCoder(
          application.getInput(), this);
    } catch (CannotProvideCoderException exc) {
      inputCoderException = exc;
    }

    // Build up the error message and list of causes.
    StringBuilder messageBuilder = new StringBuilder()
        .append("Unable to return a default Coder for ").append(this)
        .append(". Correct one of the following root causes:");

    // No exception, but give the user a message about .setCoder() has not been called.
    messageBuilder.append("\n  No Coder has been manually specified; ")
        .append(" you may do so using .setCoder().");

    if (inferFromTokenException != null) {
      messageBuilder
          .append("\n  Inferring a Coder from the CoderRegistry failed: ")
          .append(inferFromTokenException.getMessage());
    }

    if (inputCoderException != null) {
      messageBuilder
          .append("\n  Using the default output Coder from the producing PTransform failed: ")
          .append(inputCoderException.getMessage());
    }

    // Build and throw the exception.
    throw new IllegalStateException(messageBuilder.toString());
  }
}
