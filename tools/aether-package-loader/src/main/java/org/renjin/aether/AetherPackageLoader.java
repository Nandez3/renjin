package org.renjin.aether;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryListener;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.file.FileRepositoryConnectorFactory;
import org.eclipse.aether.connector.wagon.WagonProvider;
import org.eclipse.aether.connector.wagon.WagonRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.transfer.TransferListener;
import org.eclipse.aether.version.Version;
import org.renjin.primitives.packaging.ClasspathPackageLoader;
import org.renjin.primitives.packaging.FqPackageName;
import org.renjin.primitives.packaging.Package;
import org.renjin.primitives.packaging.PackageLoader;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;


public class AetherPackageLoader implements PackageLoader {
  
  private static final SettingsBuilder settingsBuilder = new DefaultSettingsBuilderFactory().newInstance();

  private static final SettingsDecrypter settingsDecrypter = new MavenSettingsDecrypter();

  private static final Logger LOGGER = Logger.getLogger(AetherPackageLoader.class.getName());

  private static Settings settings;
  
  private DynamicURLClassLoader classLoader;
  private ClasspathPackageLoader classpathPackageLoader;
  private final List<RemoteRepository> repositories = Lists.newArrayList();
  private final RepositorySystem system = newRepositorySystem();
  private final DefaultRepositorySystemSession session = newRepositorySystemSession(system);
  
  private PackageListener packageListener = null;


  /**
   * Keeps track of already-loaded packages. Each entry should be in the form groupId:artifactId
   */
  private Set<String> loadedPackages = new HashSet<String>();

  public AetherPackageLoader() {
    
    // Create our own ClassLoader to which we can add additional packages at runtime
    classLoader = new DynamicURLClassLoader(getClass().getClassLoader());
    classpathPackageLoader = new ClasspathPackageLoader(classLoader);

    repositories.add(new RemoteRepository.Builder("central", "default", "http://repo1.maven.org/maven2/").build());
    repositories.add(new RemoteRepository.Builder("renjin", "default", "http://nexus.bedatadriven.com/content/groups/public/").build());
    
    // Ensure that we don't load old versions of renjin onto the classpath
    // that might conflict with the current version.
    loadedPackages.add("org.renjin:renjin-core");
    loadedPackages.add("org.renjin:renjin-appl");
    loadedPackages.add("org.renjin:renjin-gnur-runtime");
    loadedPackages.add("org.renjin:stats");
    loadedPackages.add("org.renjin:methods");
    loadedPackages.add("org.renjin:utils");
    loadedPackages.add("org.renjin:datasets");
    loadedPackages.add("org.renjin:graphics");
    loadedPackages.add("org.renjin:grDevices");
  }

  @Override
  public Optional<Package> load(FqPackageName name) {
    Optional<Package> pkg = classpathPackageLoader.load(name);
    if (pkg.isPresent()) {
      FqPackageName packageName = pkg.get().getName();
      loadedPackages.add(packageName.getGroupId() + ":" + packageName.getPackageName());
      return pkg;
    }
    try {
      
      if(packageListener != null) {
        packageListener.packageLoading(name);
      }

      Artifact latestArtifact = resolveLatestArtifact(name);

      if (latestArtifact == null) {
        if (packageListener != null) {
          packageListener.packageVersionResolutionFailed(name);
        }
        return Optional.absent();
      }

      if(packageListener != null) {
        packageListener.packageResolved(name, latestArtifact.getVersion());
      }
      
      CollectRequest collectRequest = new CollectRequest();
      collectRequest.setRoot(new Dependency(latestArtifact, null));
      collectRequest.setRepositories(repositories);
      
      DependencyNode node = system.collectDependencies(session, collectRequest).getRoot();
      
      DependencyRequest dependencyRequest = new DependencyRequest();
      dependencyRequest.setRoot(node);
      dependencyRequest.setFilter(new AetherExclusionFilter(loadedPackages));
      dependencyRequest.setCollectRequest(collectRequest);
      
      DependencyResult dependencyResult = system.resolveDependencies(session, dependencyRequest);

      for (ArtifactResult dependency : dependencyResult.getArtifactResults()) { 
        Artifact artifact = dependency.getArtifact();
        loadedPackages.add(artifact.getGroupId() + ":" + artifact.getArtifactId());
        classLoader.addArtifact(dependency);
      }


      if(packageListener != null) {
        packageListener.packageLoadSucceeded(name, latestArtifact.getVersion());
      }


      return classpathPackageLoader.load(name);
      
    } catch (DependencyResolutionException e) {
      packageListener.packageResolveFailed(e);
      return Optional.absent();
      
    } catch (Exception e) {
      
      throw new RuntimeException(e);
    }
  }
  
  private Artifact resolveLatestArtifact(FqPackageName name) throws VersionRangeResolutionException {
    Artifact artifact = new DefaultArtifact(name.getGroupId(), name.getPackageName(), "jar", "[0,)");
    Version newestVersion = resolveLatestVersion(artifact);
    if (newestVersion == null) {
      return null;
    }
    return artifact.setVersion(newestVersion.toString());
  }

  private Version resolveLatestVersion(Artifact artifact) throws VersionRangeResolutionException {
    VersionRangeRequest rangeRequest = new VersionRangeRequest();
    rangeRequest.setArtifact(artifact);
    rangeRequest.setRepositories(repositories);

    VersionRangeResult rangeResult = system.resolveVersionRange(session, rangeRequest);

    return rangeResult.getHighestVersion();
  }

  public static RepositorySystem newRepositorySystem() {
    /*
    * Aether's components implement org.eclipse.aether.spi.locator.Service to ease manual wiring and using the
    * prepopulated DefaultServiceLocator, we only need to register the repository connector factories.
    */
    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.addService(RepositoryConnectorFactory.class, FileRepositoryConnectorFactory.class);
    locator.addService(RepositoryConnectorFactory.class, WagonRepositoryConnectorFactory.class);
    locator.setServices(WagonProvider.class, new ManualWagonProvider());

    return locator.getService(RepositorySystem.class);
  }

  public static DefaultRepositorySystemSession newRepositorySystemSession(RepositorySystem system) {
    
    
    DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
    
    System.out.println("Using local repository: " + getLocalRepositoryDir());
    
    LocalRepository localRepo = new LocalRepository(getLocalRepositoryDir());
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
    
    return session;
  }

  public void setTransferListener(TransferListener listener) {
    session.setTransferListener(listener);
  }
  
  public void setRepositoryListener(RepositoryListener listener) {
    session.setRepositoryListener(listener);
  }
  
  public void setPackageListener(PackageListener listener) {
    this.packageListener = listener;
  }
  
  private static File getLocalRepositoryDir() {
    Settings settings = getSettings();
    if ( settings.getLocalRepository() != null )
    {
      return new File( settings.getLocalRepository() );
    }

    return new File( getMavenUserHome(), "repository" );
  }

  public static File getUserSettings()
  {
    return new File(getMavenUserHome(), "settings.xml" );
  }

  private static File getMavenUserHome() {
    return new File( getUserHome(), ".m2" );
  }

  private static File getUserHome() {
    return new File( System.getProperty("user.home") );
  }

  private static File findGlobalSettings() {
    File mavenHome = getMavenHome();
    if ( mavenHome != null )
    {
      return new File( new File( mavenHome, "conf" ), "settings.xml" );
    }
    return null;
  }

  private static File getMavenHome() {
    if(!Strings.isNullOrEmpty(System.getenv("M2_HOME"))) {
      return new File(System.getenv("M2_HOME"));
    }
    String paths[] = Strings.nullToEmpty(System.getenv("PATH")).split(File.pathSeparator);
    for(String path : paths) {
      File pathDir = new File(path);
      if(pathDir.isDirectory()) {
        File bin = new File(pathDir, "bin");
        if(new File(bin, "mvn").exists() || new File(bin, "mvn.bat").exists()) {
          return pathDir;
        }
      }
    }
    return null;
  }

  private static synchronized Settings getSettings() {
    if ( settings == null ) {
      DefaultSettingsBuildingRequest request = new DefaultSettingsBuildingRequest();
      request.setUserSettingsFile(getUserSettings());
      File globalSettings = findGlobalSettings();
      if(globalSettings != null) {
        request.setGlobalSettingsFile(globalSettings);
      }

      try
      {
        settings = settingsBuilder.build( request ).getEffectiveSettings();
      }
      catch ( SettingsBuildingException e )
      {
        LOGGER.warning("Could not process settings.xml: " + e.getMessage());
      }

      SettingsDecryptionResult result =
              settingsDecrypter.decrypt( new DefaultSettingsDecryptionRequest( settings ) );
      settings.setServers( result.getServers() );
      settings.setProxies( result.getProxies() );
    }
    return settings;
  }

  public DynamicURLClassLoader getClassLoader() {
    return classLoader;
  }
}
