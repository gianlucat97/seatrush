-- The compensations a SAGA is still waiting to hear about.
--
-- A compensating checkout sends one or two commands depending on why it failed (README section
-- 8.3): a declined payment only has to release the hold, while a failed commit and a deadline both
-- void the payment as well. It becomes FAILED once every one of them has been acknowledged, so it
-- has to remember which ones are still outstanding.
--
-- A set and not a counter, and that is the whole reason this column exists. README section 8.3 says
-- a late PaymentAuthorized arriving at a compensating SAGA triggers another VoidPayment, which
-- produces a second PaymentVoided under a new message id. A counter would be decremented twice by
-- those two acknowledgements and the SAGA would declare itself finished while the hold was still
-- held. Removing a name from a set is idempotent however often it arrives.
--
-- A separate migration rather than an edit of V1, because V1 has been applied: Flyway validates the
-- checksum of what it already ran, and editing it would break every database that has it, local
-- Compose volumes included.

ALTER TABLE saga_instance
    ADD COLUMN compensations_pending TEXT NOT NULL DEFAULT '';

COMMENT ON COLUMN saga_instance.compensations_pending IS
    'Comma separated VOID_PAYMENT and RELEASE_HOLD still waiting for their replies';

-- Only the two compensations of README section 8.3, in any combination including none.
ALTER TABLE saga_instance
    ADD CONSTRAINT saga_instance_compensations_known
        CHECK (compensations_pending ~ '^(VOID_PAYMENT|RELEASE_HOLD)?(,(VOID_PAYMENT|RELEASE_HOLD))*$');

-- A SAGA that is not compensating waits for no compensation, and one that still waits for a
-- compensation has not finished compensating. The two have to agree, or a checkout could be
-- reported as failed while its seats were still held.
ALTER TABLE saga_instance
    ADD CONSTRAINT saga_instance_compensations_match_status
        CHECK (compensations_pending = '' OR status = 'COMPENSATING');
