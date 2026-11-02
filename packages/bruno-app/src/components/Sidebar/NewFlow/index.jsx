import React, { useRef } from 'react';
import { useFormik } from 'formik';
import * as Yup from 'yup';
import toast from 'react-hot-toast';
import { useDispatch, useSelector } from 'react-redux';
import Modal from 'components/Modal';
import Portal from 'components/Portal';
import { newFlow } from 'providers/ReduxStore/slices/collections/actions';
import { sanitizeName, validateName, validateNameError } from 'utils/common/regex';

const NewFlow = ({ collectionUid, item, onClose }) => {
  const dispatch = useDispatch();
  const submitLockRef = useRef(false);

  const collection = useSelector((state) =>
    state.collections.collections?.find((c) => c.uid === collectionUid)
  );

  const formik = useFormik({
    enableReinitialize: true,
    initialValues: { flowName: '' },
    validationSchema: Yup.object({
      flowName: Yup.string()
        .trim()
        .min(1, 'Flow name is required')
        .max(255, 'Must be 255 characters or less')
        .test('valid-name', validateNameError, (value) => validateName(value || ''))
        .required('Flow name is required')
    }),
    onSubmit: (values) => {
      const name = values.flowName.trim();
      return dispatch(
        newFlow({
          flowName: name,
          filename: sanitizeName(name),
          collectionUid,
          itemUid: item ? item.uid : null
        })
      )
        .then(() => {
          toast.success('Flow created');
          onClose();
        })
        .catch((err) => toast.error(err?.message || 'Failed to create flow'))
        .finally(() => {
          submitLockRef.current = false;
        });
    }
  });

  const onSubmit = () => {
    if (submitLockRef.current || formik.isSubmitting) return;
    submitLockRef.current = true;
    formik.handleSubmit();
    setTimeout(() => {
      submitLockRef.current = false;
    }, 0);
  };

  return (
    <Portal>
      <Modal
        size="md"
        title="New Flow"
        confirmText="Create"
        handleConfirm={onSubmit}
        handleCancel={onClose}
        disableEscapeKey={false}
        disableCloseOnOutsideClick={false}
        confirmDisabled={formik.isSubmitting}
      >
        <form
          className="bruno-form"
          onSubmit={(e) => e.preventDefault()}
          data-testid="new-flow-form"
        >
          <label htmlFor="flowName" className="block font-semibold">
            Name
          </label>
          <input
            id="flowName"
            type="text"
            name="flowName"
            data-testid="new-flow-name-input"
            autoFocus
            autoComplete="off"
            spellCheck="false"
            className="block textbox mt-2 w-full"
            value={formik.values.flowName}
            onChange={formik.handleChange}
          />
          {formik.touched.flowName && formik.errors.flowName ? (
            <div className="text-red-500 text-xs mt-2">{formik.errors.flowName}</div>
          ) : (
            <div className="text-xs mt-2 opacity-70">
              Creates a visual API workflow in {item ? 'this folder' : `collection "${collection?.name || ''}"`}.
            </div>
          )}
        </form>
      </Modal>
    </Portal>
  );
};

export default NewFlow;
