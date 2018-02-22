/* (c) 2017 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.taskmanager.web;

import org.geoserver.taskmanager.data.Configuration;
import org.geoserver.taskmanager.util.TaskManagerBeans;
import org.geoserver.taskmanager.web.model.ConfigurationsModel;
import org.geoserver.taskmanager.web.panel.DropDownPanel;
import org.geoserver.taskmanager.web.panel.MultiLabelCheckBoxPanel;
import org.geoserver.web.ComponentAuthorizer;
import org.geoserver.web.GeoServerSecuredPage;
import org.geoserver.web.wicket.GeoServerDialog;
import org.geoserver.web.wicket.GeoServerTablePanel;
import org.geoserver.web.wicket.ParamResourceModel;
import org.geoserver.web.wicket.SimpleAjaxLink;
import org.geoserver.web.wicket.GeoServerDataProvider.Property;
import org.geotools.util.logging.Logging;

import static org.apache.commons.lang.StringEscapeUtils.escapeHtml;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.extensions.ajax.markup.html.modal.ModalWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AbstractConfigurationsPage extends GeoServerSecuredPage {

    private static final long serialVersionUID = -6780935404517755471L;
    
    private static final Logger LOGGER = Logging.getLogger(AbstractConfigurationsPage.class);
       
    private boolean templates;

    private AjaxLink<Object> remove;

    private AjaxLink<Object> copy;
    
    private GeoServerDialog dialog;
    
    private GeoServerTablePanel<Configuration> configurationsPanel;
    
    public AbstractConfigurationsPage(boolean templates) {
        this.templates = templates;
    }

    protected ComponentAuthorizer getPageAuthorizer() {
        return ComponentAuthorizer.AUTHENTICATED;
    }
    
    @Override
    public void onInitialize() {
        super.onInitialize();
        
        add(dialog = new GeoServerDialog("dialog"));
        dialog.setInitialHeight(150); 
        ((ModalWindow) dialog.get("dialog")).showUnloadConfirmation(false); 

        add(new AjaxLink<Object>("addNew") {
            private static final long serialVersionUID = 3581476968062788921L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                if (templates || TaskManagerBeans.get().getDao().getConfigurations(true).isEmpty()) {
                    Configuration configuration = TaskManagerBeans.get().getFac().createConfiguration();
                    configuration.setTemplate(templates);

                    setResponsePage(new ConfigurationPage(configuration));

                } else {
                    dialog.setTitle(new ParamResourceModel("addNewDialog.title", getPage()));

                    dialog.showOkCancel(target, new GeoServerDialog.DialogDelegate() {

                        private static final long serialVersionUID = -5552087037163833563L;

                        private DropDownPanel panel;

                        @Override
                        protected Component getContents(String id) {
                            ArrayList<String> list = new ArrayList<String>();
                            for (Configuration template : TaskManagerBeans.get().getDao()
                                    .getConfigurations(true)) {
                                list.add(template.getName());
                            }
                            panel = new DropDownPanel(id, new Model<String>(),
                                    new Model<ArrayList<String>>(list), new ParamResourceModel(
                                            "addNewDialog.chooseTemplate", getPage()));
                            return panel;
                        }

                        @Override
                        protected boolean onSubmit(AjaxRequestTarget target, Component contents) {
                            String choice = (String) panel.getDefaultModelObject();
                            Configuration configuration;
                            if (choice == null) {
                                configuration = TaskManagerBeans.get().getFac().createConfiguration();
                            } else {
                                configuration = TaskManagerBeans.get().getDao()
                                    .copyConfiguration(choice);
                                configuration.setTemplate(false);
                                configuration.setName(null);
                            }

                            setResponsePage(
                                    new ConfigurationPage(configuration));

                            return true;
                        }

                    });
                }
            }
        });
        
        // the removal button
        add(remove = new AjaxLink<Object>("removeSelected") {
            private static final long serialVersionUID = 3581476968062788921L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                List<String> nonDeletable = new ArrayList<String>();
                for (Configuration config : configurationsPanel.getSelection()) {
                    if (!TaskManagerBeans.get().getDataUtil().isDeletable(config)) {
                        nonDeletable.add(config.getName());
                    }
                }
                if (!nonDeletable.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(new ParamResourceModel("cannotDelete",
                           AbstractConfigurationsPage.this).getString());
                    for (String batchName : nonDeletable) {
                        sb.append(escapeHtml(batchName)).append(", ");
                    }              
                    sb.setLength(sb.length() - 2);
                    error(sb.toString());
                    target.add(feedbackPanel);
                } else {
                
                    dialog.setTitle(new ParamResourceModel("confirmDeleteDialog.title", getPage()));
                    dialog.showOkCancel(target, new GeoServerDialog.DialogDelegate() {
    
                        private static final long serialVersionUID = -5552087037163833563L;
                        
                        private String error = null;
                        
                        private IModel<Boolean> shouldCleanupModel = new Model<Boolean>();
    
                        @Override
                        protected Component getContents(String id) {
                            StringBuilder sb = new StringBuilder();
                            sb.append(new ParamResourceModel("confirmDeleteDialog.content",
                                    getPage()).getString());
                            for (Configuration config : configurationsPanel.getSelection()) {
                                sb.append("\n&nbsp;&nbsp;");
                                sb.append(escapeHtml(config.getName()));
                            }
                            return new MultiLabelCheckBoxPanel(id, sb.toString(),
                                new ParamResourceModel("cleanUp", getPage()).getString(),
                                shouldCleanupModel);
                        }
    
                        @Override
                        protected boolean onSubmit(AjaxRequestTarget target, Component contents) {
                            try {
                                for (Configuration config : configurationsPanel.getSelection()) {
                                    if (shouldCleanupModel.getObject()) {
                                        if (TaskManagerBeans.get().getTaskUtil().canCleanup(config)) {
                                            if (TaskManagerBeans.get().getTaskUtil().cleanup(config)) {
                                                info(new ParamResourceModel("cleanUp.success",
                                                        getPage(), config.getName()).getString());                                                   
                                            } else {
                                                error(new ParamResourceModel("cleanUp.error",
                                                        getPage(), config.getName()).getString());    
                                            }
                                        } else {
                                            info(new ParamResourceModel("cleanUp.ignore",
                                                    getPage(), config.getName()).getString());   
                                        }
                                    }
                                    TaskManagerBeans.get().getDao().remove(config);
                                }
                                configurationsPanel.clearSelection();
                                remove.setEnabled(false);
                            } catch (Exception e) {
                                LOGGER.log(Level.WARNING, e.getMessage(), e);
                                Throwable rootCause = ExceptionUtils.getRootCause(e);
                                error = rootCause == null ? e.getLocalizedMessage() : 
                                    rootCause.getLocalizedMessage();
                            }
                            return true;
                        }
                        
                        @Override
                        public void onClose(AjaxRequestTarget target) {
                            if (error != null) {
                                error(error);
                            }
                            target.add(feedbackPanel);
                            target.add(configurationsPanel);
                            target.add(remove);
                        }
                    });
                }
            }  
        });
        remove.setOutputMarkupId(true);
        remove.setEnabled(false);
        
        // the removal button
        add(copy = new AjaxLink<Object>("copySelected") {
            private static final long serialVersionUID = 3581476968062788921L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                Configuration copy = TaskManagerBeans.get().getDao().copyConfiguration(
                        configurationsPanel.getSelection().get(0).getName());
                
                setResponsePage(new ConfigurationPage(copy));
            }
        });
        copy.setOutputMarkupId(true);
        copy.setEnabled(false);
                
        //the panel
        add(configurationsPanel =  new GeoServerTablePanel<Configuration>("configurationsPanel", 
                new ConfigurationsModel(templates), true) {

            private static final long serialVersionUID = -8943273843044917552L;

            @Override
            protected void onSelectionUpdate(AjaxRequestTarget target) {
                remove.setEnabled(configurationsPanel.getSelection().size() > 0);
                copy.setEnabled(configurationsPanel.getSelection().size() == 1);
                target.add(remove);
                target.add(copy);
            }

            @SuppressWarnings("unchecked")
            @Override
            protected Component getComponentForProperty(String id, IModel<Configuration> itemModel,
                    Property<Configuration> property) {
                if (property.equals(ConfigurationsModel.NAME)) {
                    return new SimpleAjaxLink<String>(id, (IModel<String>) property.getModel(itemModel)) {
                        private static final long serialVersionUID = -9184383036056499856L;
                        
                        @Override
                        protected void onClick(AjaxRequestTarget target) {
                            setResponsePage(new ConfigurationPage(itemModel));
                        }
                    };                    
                }
                return null;
            }
        });
        configurationsPanel.setOutputMarkupId(true);
    }

}