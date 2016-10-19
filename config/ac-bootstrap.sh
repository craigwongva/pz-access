#!/usr/bin/env bash

# Update repository to fetch latest OpenJDK
sudo add-apt-repository -y ppa:openjdk-r/ppa
sudo apt-get -y update

# Install required packages
sudo apt-get -y install openjdk-8-jdk maven

# Build the Access application
cd /vagrant/access
mvn clean package

# Updating hosts
echo "192.168.23.23	jobmanager.dev" >> /etc/hosts
echo "192.168.33.12  kafka.dev" >> /etc/hosts

# Add an Upstart job to run our script upon machine boot
chmod 777 /vagrant/access/config/spring-start.sh
cp /vagrant/access/config/access.conf /etc/init/access.conf

# Run the Access application
cd /vagrant/access/config
./spring-start.sh