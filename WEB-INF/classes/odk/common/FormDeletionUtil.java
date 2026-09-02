package odk.common;

import org.opendatakit.aggregate.form.FormFactory;
import org.opendatakit.aggregate.form.IForm;
import org.opendatakit.aggregate.form.MiscTasks;
import org.opendatakit.aggregate.task.FormDeleteWorkerImpl;
import org.opendatakit.common.web.CallingContext;

/**
 * Supprime definitivement un formulaire (definition + toutes ses soumissions),
 * en reutilisant la logique interne d'ODK (celle que l'onglet natif "Form
 * Management" declenche en tache de fond) plutot que de reimplementer la
 * suppression en SQL brut - supprimer un formulaire touche de nombreuses
 * tables liees (_form_info, _form_data_model, filtres, fichiers, etc.).
 *
 * FormDeleteWorkerImpl.deleteForm() exige un enregistrement MiscTasks reellement
 * persiste (confirme par lecture du bytecode : sans ca, l'appel echoue en
 * silence, sans exception, sans rien supprimer) - d'ou la creation + persist()
 * avant l'appel, exactement comme le fait FormAdminServiceImpl en interne.
 */
public final class FormDeletionUtil {

    private FormDeletionUtil() {
    }

    public static void deleteForm(String formId, CallingContext cc) throws Exception {
        IForm form = FormFactory.retrieveFormByFormId(formId, cc);
        MiscTasks task = new MiscTasks(MiscTasks.TaskType.DELETE_FORM, form, null, cc);
        task.persist(cc);
        new FormDeleteWorkerImpl(form, task.getSubmissionKey(), 1L, cc).deleteForm();
    }
}
