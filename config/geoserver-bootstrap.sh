#!/usr/bin/env bash

# Update repository to fetch latest OpenJDK
sudo add-apt-repository -y ppa:openjdk-r/ppa
sudo apt-get -y update

# Install required packages
sudo apt-get -y install openjdk-8-jdk tomcat7 unzip

# Ensure Tomcat is pointing to JDK8
sudo echo 'JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-amd64' >> /etc/default/tomcat7

# Update hosts to Database
sudo echo "192.168.23.25  postgis.dev" >> /etc/hosts

# Download and install GeoServer
wget http://sourceforge.net/projects/geoserver/files/GeoServer/2.8.2/geoserver-2.8.2-war.zip
unzip geoserver-2.8.2-war.zip geoserver.war
sudo mv geoserver.war /var/lib/tomcat7/webapps/
sudo service tomcat7 restart

# Create the Data Store for the PostGIS Server
sudo cp -r /vagrant/access/config/geoserver-files/workspaces/piazza /var/lib/tomcat7/webapps/geoserver/data/workspaces
sudo service tomcat7 restart