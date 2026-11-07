import React, { useRef } from 'react';
import { useFormik } from 'formik';
import * as Yup from 'yup';
import toast from 'react-hot-toast';
import { useDispatch, useSelector } from 'react-redux';
import Modal from 'components/Modal';
import Portal from 'components/Portal';
import { newFlow } from 'providers/ReduxStore/slices/collections/actions';
import { sanitizeName, validateName, validateNameError } from 'utils/common/regex';
import { flattenItems, isItemARequest } from 'utils/collections';
import { getRelativePath } from 'utils/common/path';
import { FLOW_TEMPLATES, createFlowTemplate } from 'components/FlowTabPanel/starter-templates';

const NewFlow = ({ collectionUid, item, onClose }) => {
  const dispatch = useDispatch();
  const submitLockRef = useRef(false);

  const collection = useSelector((state) =>
    state.collections.collections?.find((c) => c.uid === collectionUid)
  );

  const formik = useFormik({
    enableReinitialize: true,
    initialValues: { flowName: '', template: 'blank', references: {} },
    validate: (values) => {
      const template = FLOW_TEMPLATES.find((entry) => entry.id === values.template);
      if (!template) return { template: 'Choose a valid starter template' };
      return template.requests.some(([id]) => !values.references[id]) ? { references: 'Select the requests for this template' } : {};
    },
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
          itemUid: item ? item.uid : null,
          initialFlow: createFlowTemplate(values.template, values.references)
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
          <label className="block mt-3">Starter template
            <select aria-label="Starter template" className="textbox w-full mt-1" value={formik.values.template} onChange={(event) => formik.setFieldValue('template', event.target.value)}>
              {FLOW_TEMPLATES.map((template) => <option key={template.id} value={template.id}>{template.name}</option>)}
            </select>
          </label>
          {(FLOW_TEMPLATES.find((template) => template.id === formik.values.template)?.requests || []).map(([id, label]) => (
            <label key={id} className="block mt-3">{label}
              <select aria-label={label} className="textbox w-full mt-1" value={formik.values.references[id] || ''} onChange={(event) => formik.setFieldValue(`references.${id}`, event.target.value)}>
                <option value="">Choose a saved request</option>
                {flattenItems(collection?.items || []).filter(isItemARequest).map((request) => <option key={request.uid} value={getRelativePath(collection.pathname, request.pathname, true)}>{request.name}</option>)}
              </select>
            </label>
          ))}
          {formik.errors.template && <p role="alert" className="text-xs text-red-500 mt-2">{formik.errors.template}</p>}
          {formik.errors.references && <p role="alert" className="text-xs text-red-500 mt-2">{formik.errors.references}</p>}
          {formik.values.template === 'batch' && <p className="text-xs mt-2">Add data.csv to the collection root and map ctx.row fields to the processing request variables.</p>}
          {formik.values.template === 'poll' && <p className="text-xs mt-2">Polls every 2 seconds, up to 30 times. Adapt the DONE status check and map the job ID from your submit response.</p>}
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
