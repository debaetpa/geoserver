package org.geoserver.taskmanager.tasks;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.CatalogFactory;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.NamespaceInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.Wrapper;
import org.geoserver.catalog.impl.CatalogFactoryImpl;
import org.geoserver.taskmanager.external.ExtTypes;
import org.geoserver.taskmanager.schedule.BatchContext;
import org.geoserver.taskmanager.schedule.ParameterInfo;
import org.geoserver.taskmanager.schedule.ParameterType;
import org.geoserver.taskmanager.schedule.TaskContext;
import org.geoserver.taskmanager.schedule.TaskException;
import org.geoserver.taskmanager.schedule.TaskResult;
import org.geoserver.taskmanager.schedule.TaskType;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.opengis.feature.type.Name;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FileLocalPublicationTaskTypeImpl implements TaskType {
    
    public static final String NAME = "LocalFilePublication";
    
    public static final String PARAM_LAYER = "layer";
    
    public static final String PARAM_FILE = "file";
    
    protected final Map<String, ParameterInfo> paramInfo = new LinkedHashMap<String, ParameterInfo>();

    @Autowired
    protected ExtTypes extTypes;
    
    @Autowired
    protected Catalog catalog;

    @Override
    public String getName() {
        return NAME;
    }

    @PostConstruct
    public void initParamInfo() {
        paramInfo.put(PARAM_FILE, new ParameterInfo(PARAM_FILE, ParameterType.UPLOADABLE_URI, true));
        paramInfo.put(PARAM_LAYER, new ParameterInfo(PARAM_LAYER, extTypes.layerName, true));
    }

    @Override
    public Map<String, ParameterInfo> getParameterInfo() {
        return paramInfo;
    }

    @Override
    public TaskResult run(TaskContext ctx) throws TaskException {
        CatalogFactory catalogFac = new CatalogFactoryImpl(catalog);
        
        final Name layerName = (Name) ctx.getParameterValues().get(PARAM_LAYER);        
        final NamespaceInfo ns = catalog.getNamespaceByURI(layerName.getNamespaceURI());
        final WorkspaceInfo ws = catalog.getWorkspaceByName(ns.getName());
        
        final URI uri = (URI) ctx.getBatchContext().get(ctx.getParameterValues().get(PARAM_FILE),
                new BatchContext.Dependency() {
            @Override
            public void revert() throws TaskException {
                 StoreInfo store = catalog.getStoreByName(ws, layerName.getLocalPart(), StoreInfo.class);
                 URI uri = (URI) ctx.getBatchContext().get(ctx.getParameterValues().get(PARAM_FILE));
                 if (store instanceof CoverageStoreInfo) {
                     ((CoverageStoreInfo) store).setURL(uri.toString());
                 } else {
                     try {
                        store.getConnectionParameters().put("url", uri.toURL());
                    } catch (MalformedURLException e) {
                        throw new TaskException(e);
                    }
                 }
                 catalog.save(store);
            }
        });   
        
        final boolean createLayer = catalog.getLayerByName(layerName) == null;
        final boolean createStore;
        final boolean createResource;
        
        final LayerInfo layer;
        final StoreInfo store;
        final ResourceInfo resource;
        
        URL url;
        try {
            url = uri.toURL();
        } catch (MalformedURLException e1) {
            url = null;
        }
        final boolean isShapeFile = url != null && url.getFile().toUpperCase().endsWith(".SHP");
                
        if (createLayer) {
            final StoreInfo _store = catalog.getStoreByName(ws, layerName.getLocalPart(), 
                    StoreInfo.class);
            final CoverageInfo _resource = catalog.getResourceByName(layerName, 
                    CoverageInfo.class);
            createStore = _store == null;
            createResource = _resource == null;
            
            if (createStore) {
                store = isShapeFile ? catalogFac.createDataStore() : catalogFac.createCoverageStore();
                store.setWorkspace(ws);
                store.setName(layerName.getLocalPart());
                if (isShapeFile) {
                    store.getConnectionParameters().put("url", url);
                } else {
                    ((CoverageStoreInfo) store).setType(url == null ? determineFormatFromSpecialScheme(uri.getScheme())
                            : GridFormatFinder.findFormat(url).getName());
                    ((CoverageStoreInfo) store).setURL(uri.toString());
                }
                store.setEnabled(true);
                catalog.add(store);
            } else {
                store = unwrap(_store, StoreInfo.class);
            }
            
            CatalogBuilder builder = new CatalogBuilder(catalog);
            if (createResource) {
                builder.setStore(store);
                try {
                    if (isShapeFile) {
                        resource = builder.buildFeatureType(((ShapefileDataStore) ((DataStoreInfo) store).getDataStore(null))
                                .getFeatureSource());
                    } else {
                        resource = builder.buildCoverage();
                    }
                    resource.setName(layerName.getLocalPart());
                    resource.setTitle(layerName.getLocalPart());
                    resource.setAdvertised(false);
                } catch (Exception e) {
                    if (createStore) {
                        catalog.remove(store);
                    }
                    throw new TaskException(e);
                }
                catalog.add(resource);
            } else {
                resource = unwrap(_resource, CoverageInfo.class);
            }
            
            layer = builder.buildLayer(resource);
            catalog.add(layer);     
        } else {
            layer = null;
            resource = null;
            store = null;
            createStore = false;
            createResource = false;
        }
        
        return new TaskResult() {

            @Override
            public void commit() throws TaskException {
                if (createResource) {
                    ResourceInfo editResource = catalog.getResource(layer.getId(), ResourceInfo.class);
                    editResource.setAdvertised(true);
                    catalog.save(editResource);
                }
            }

            @Override
            public void rollback() throws TaskException {
                if (createLayer) {
                    catalog.remove(layer);
                    if (createResource) {
                        catalog.remove(resource);
                    }
                    if (createStore) {
                        catalog.remove(store);
                    }
                }
            }
            
        };        
       
    }

    @Override
    public void cleanup(TaskContext ctx) throws TaskException {
        final Name layerName = (Name) ctx.getParameterValues().get(PARAM_LAYER);
        final String workspace = catalog.getNamespaceByURI(layerName.getNamespaceURI()).getPrefix();

        final LayerInfo layer = catalog.getLayerByName(layerName);               
        final StoreInfo store = catalog.getStoreByName(workspace, layerName.getLocalPart(), StoreInfo.class);
        final ResourceInfo resource = catalog.getResourceByName(layerName, 
                ResourceInfo.class);
        
        catalog.remove(layer);
        catalog.remove(resource);
        catalog.remove(store);
    }
    
    private static String determineFormatFromSpecialScheme(String scheme) {
        //currently only S3GeoTiff supports schemes other than File and HTTP
        return "S3GeoTiff";
    }
    
    private static <T> T unwrap(T o, Class<T> clazz) {
        if (o instanceof Wrapper) {
            return ((Wrapper) o).unwrap(clazz);
        } else {
            return o;
        }
    }

}
